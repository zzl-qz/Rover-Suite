"""Zero-dependency Rover HTTP registrar reference implementation.

This is intentionally not a full service-discovery SDK.  It only owns the
register/heartbeat/unregister lifecycle for one externally reachable endpoint.
Call ``start()`` after the application is listening/ready and ``close()`` before
stopping that listener.
"""

from __future__ import annotations

import json
import logging
import threading
import uuid
from dataclasses import dataclass
from typing import Any, Dict, Mapping, Optional
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


_PATHS = {
    "register": "/v1/client/instances/register",
    "heartbeat": "/v1/client/instances/heartbeat",
    "unregister": "/v1/client/instances/unregister",
}
_PERMANENT_HTTP_STATUSES = {400, 401, 403, 409, 413}
_MAX_RESPONSE_BYTES = 64 * 1024


@dataclass(frozen=True)
class _Outcome:
    action: str
    error: Optional[Exception] = None
    body: Optional[Mapping[str, Any]] = None


class RoverRegistrar:
    """Maintain one Rover registration lease on a single background thread."""

    def __init__(
        self,
        *,
        nameserver_url: str,
        service_name: str,
        instance_id: str,
        host: str,
        port: int,
        token: str = "",
        weight: int = 100,
        group: Optional[str] = None,
        zone: Optional[str] = None,
        metadata: Optional[Mapping[str, str]] = None,
        retry_interval_seconds: float = 5.0,
        heartbeat_interval_seconds: float = 5.0,
        request_timeout_seconds: float = 3.0,
        logger: Optional[logging.Logger] = None,
    ) -> None:
        self.nameserver_url = _valid_url(nameserver_url)
        self.service_name = _required_string(service_name, "service_name")
        self.instance_id = _required_string(instance_id, "instance_id")
        self.host = _required_string(host, "host")
        self.port = _positive_int(port, "port")
        if self.port > 65535:
            raise ValueError("port must be <= 65535")
        self.token = str(token) if token is not None else ""
        self.weight = _positive_int(weight, "weight")
        self.group = None if group is None else str(group)
        self.zone = None if zone is None else str(zone)
        self.metadata: Dict[str, str] = dict(metadata or {})
        self.retry_interval_seconds = _bounded_interval(
            retry_interval_seconds, "retry_interval_seconds"
        )
        self.heartbeat_interval_seconds = _bounded_interval(
            heartbeat_interval_seconds, "heartbeat_interval_seconds"
        )
        self.request_timeout_seconds = _positive_number(
            request_timeout_seconds, "request_timeout_seconds"
        )
        self.logger = logger or logging.getLogger(__name__)

        self.session_id = str(uuid.uuid4())
        self.last_error: Optional[Exception] = None
        self._state = "IDLE"
        self._lock = threading.Lock()
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._closing = False
        self._unregister_attempted = False

    @property
    def state(self) -> str:
        with self._lock:
            return self._state

    def start(self) -> "RoverRegistrar":
        """Start registration without blocking application startup."""
        with self._lock:
            if self._state != "IDLE":
                return self
            self._state = "REGISTERING"
            self._thread = threading.Thread(
                target=self._run,
                name="rover-http-registrar",
                daemon=True,
            )
            thread = self._thread
        thread.start()
        return self

    def close(self) -> None:
        """Stop scheduling and make at most one best-effort unregister request."""
        with self._lock:
            if self._state == "CLOSED":
                return
            if self._state == "IDLE":
                self._state = "CLOSED"
                return
            self._closing = True
            self._state = "STOPPING"
            thread = self._thread
            self._stop_event.set()

        if thread is not None and thread is not threading.current_thread():
            # 正常 HTTP 请求受 request_timeout 约束；额外 1 秒留给注销请求收尾。
            thread.join(self.request_timeout_seconds * 2 + 1.0)

        if thread is None or not thread.is_alive():
            self._best_effort_unregister()
            self._set_state("CLOSED")
        else:
            # 不并发启动第二个请求；后台线程结束当前超时请求后会自行注销。
            self.logger.warning("Rover registrar is still stopping; unregister remains best-effort")

    def _run(self) -> None:
        operation = "register"
        try:
            while not self._stop_event.is_set():
                outcome = self._request(operation)
                if self._stop_event.is_set():
                    break

                if outcome.action == "PERMANENT_FAILURE":
                    self.last_error = outcome.error
                    self._set_state("FAILED")
                    self.logger.error("Rover registrar stopped: %s", outcome.error)
                    # 保持线程存活但不重试；close() 会唤醒并在同一线程注销。
                    self._stop_event.wait()
                    break

                if operation == "register":
                    if outcome.action == "SUCCESS":
                        self.last_error = None
                        self._set_state("REGISTERED")
                        operation = "heartbeat"
                        delay = self.heartbeat_interval_seconds
                    else:
                        self.last_error = outcome.error
                        self._set_state("REGISTERING")
                        self.logger.warning(
                            "Rover register failed; retrying in %.3fs: %s",
                            self.retry_interval_seconds,
                            outcome.error,
                        )
                        operation = "register"
                        delay = self.retry_interval_seconds
                elif outcome.action == "SUCCESS":
                    self.last_error = None
                    self._set_state("REGISTERED")
                    operation = "heartbeat"
                    delay = self.heartbeat_interval_seconds
                elif outcome.action == "REGISTER_NOW":
                    self._set_state("REGISTERING")
                    operation = "register"
                    # 心跳已完成；下一轮直接完整注册，不先等待 5 秒。
                    continue
                else:
                    self.last_error = outcome.error
                    self._set_state("REGISTERED")
                    self.logger.warning(
                        "Rover heartbeat failed; retrying in %.3fs: %s",
                        self.retry_interval_seconds,
                        outcome.error,
                    )
                    operation = "heartbeat"
                    delay = self.retry_interval_seconds

                # fixed-delay：从上一请求完成后开始等待，stop_event 可立即打断关闭。
                self._stop_event.wait(delay)
        except Exception as exc:  # 防止参考代码中的意外异常形成无声线程退出
            self.last_error = exc
            self._set_state("FAILED")
            self.logger.exception("Unexpected Rover registrar failure")
        finally:
            if self._is_closing():
                self._best_effort_unregister()
                self._set_state("CLOSED")

    def _request(self, operation: str) -> _Outcome:
        if operation == "register":
            payload: Mapping[str, Any] = {
                "serviceName": self.service_name,
                "instanceId": self.instance_id,
                "sessionId": self.session_id,
                "host": self.host,
                "port": self.port,
                "weight": self.weight,
                "group": self.group,
                "zone": self.zone,
                "metadata": self.metadata,
            }
        else:
            payload = {
                "serviceName": self.service_name,
                "instanceId": self.instance_id,
                "sessionId": self.session_id,
            }

        try:
            status, body = self._post_json(_PATHS[operation], payload)
        except (OSError, TimeoutError, URLError, ValueError, json.JSONDecodeError) as exc:
            return _Outcome("RETRY", error=exc)

        if 200 <= status < 300 and body and body.get("code") == "OK":
            return _Outcome("SUCCESS", body=body)
        if 200 <= status < 300:
            return _Outcome(
                "RETRY",
                error=ValueError("Nameserver success response must contain code=OK"),
            )

        code = str(body.get("code", "HTTP_%s" % status)) if body else "HTTP_%s" % status
        message = str(body.get("message", code)) if body else code
        error = RuntimeError("%s: %s" % (code, message))
        if (
            operation == "heartbeat"
            and status == 404
            and code == "INSTANCE_NOT_FOUND"
        ):
            return _Outcome("REGISTER_NOW", error=error)
        if status in (408, 429) or status >= 500:
            return _Outcome("RETRY", error=error)
        if status in _PERMANENT_HTTP_STATUSES or (
            400 <= status < 500
        ):
            return _Outcome("PERMANENT_FAILURE", error=error)
        return _Outcome("RETRY", error=error)

    def _post_json(
        self, path: str, payload: Mapping[str, Any]
    ) -> tuple[int, Optional[Mapping[str, Any]]]:
        content = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        headers = {"Accept": "application/json", "Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        request = Request(
            self.nameserver_url.rstrip("/") + path,
            data=content,
            headers=headers,
            method="POST",
        )
        try:
            with urlopen(request, timeout=self.request_timeout_seconds) as response:
                status = int(response.status)
                raw = response.read(_MAX_RESPONSE_BYTES + 1)
        except HTTPError as error:
            try:
                status = int(error.code)
                raw = error.read(_MAX_RESPONSE_BYTES + 1)
            finally:
                error.close()
        if len(raw) > _MAX_RESPONSE_BYTES:
            raise ValueError("Nameserver response exceeds 65536 bytes")
        if not raw:
            return status, None
        try:
            decoded = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            if status < 400:
                raise
            return status, None
        if not isinstance(decoded, dict):
            if status < 400:
                raise ValueError("Nameserver response must be a JSON object")
            return status, None
        return status, decoded

    def _best_effort_unregister(self) -> None:
        with self._lock:
            if self._unregister_attempted:
                return
            self._unregister_attempted = True
        outcome = self._request("unregister")
        if outcome.action != "SUCCESS":
            self.logger.warning("Rover unregister failed during close: %s", outcome.error)

    def _set_state(self, value: str) -> None:
        with self._lock:
            self._state = value

    def _is_closing(self) -> bool:
        with self._lock:
            return self._closing


def _valid_url(value: str) -> str:
    url = _required_string(value, "nameserver_url").rstrip("/")
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise ValueError("nameserver_url must be an absolute http(s) URL")
    return url


def _required_string(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise TypeError("%s must be a non-empty string" % name)
    return value.strip()


def _positive_int(value: Any, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise TypeError("%s must be a positive integer" % name)
    return value


def _positive_number(value: Any, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value <= 0:
        raise TypeError("%s must be positive" % name)
    return float(value)


def _bounded_interval(value: Any, name: str) -> float:
    interval = _positive_number(value, name)
    if interval > 8.0:
        raise ValueError("%s must be <= 8 seconds" % name)
    return interval


__all__ = ["RoverRegistrar"]
