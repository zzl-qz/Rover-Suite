import json
import logging
import threading
import time
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from rover_registrar import RoverRegistrar


class RoverRegistrarTest(unittest.TestCase):
    def test_retry_and_heartbeat_intervals_cannot_exceed_eight_seconds(self):
        common = {
            "nameserver_url": "http://127.0.0.1:8889",
            "service_name": "order-service",
            "instance_id": "pod-one",
            "host": "127.0.0.1",
            "port": 8080,
        }
        with self.assertRaisesRegex(ValueError, "retry_interval_seconds must be <= 8 seconds"):
            RoverRegistrar(**common, retry_interval_seconds=8.001)
        with self.assertRaisesRegex(ValueError, "heartbeat_interval_seconds must be <= 8 seconds"):
            RoverRegistrar(**common, heartbeat_interval_seconds=8.001)

    def test_2xx_without_ok_code_is_retried(self):
        counts = {"register": 0, "heartbeat": 0, "unregister": 0}

        def responder(operation):
            counts[operation] += 1
            if operation == "register" and counts[operation] == 1:
                return 200, "BROKEN_SUCCESS"
            return 200, "OK"

        harness = _Harness(responder)
        self.addCleanup(harness.close)
        registrar = RoverRegistrar(
            nameserver_url=harness.url,
            service_name="order-service",
            instance_id="pod-one",
            host="127.0.0.1",
            port=8080,
            retry_interval_seconds=0.02,
            heartbeat_interval_seconds=0.02,
            request_timeout_seconds=0.2,
            logger=_quiet_logger(),
        )

        registrar.start()
        _wait_for(lambda: counts["register"] >= 2 and registrar.state == "REGISTERED")
        registrar.close()

    def test_retry_immediate_reregister_single_inflight_and_close(self):
        counts = {"register": 0, "heartbeat": 0, "unregister": 0}

        def responder(operation):
            counts[operation] += 1
            if operation == "register" and counts[operation] == 1:
                return 408, "REQUEST_TIMEOUT"
            if operation == "heartbeat" and counts[operation] == 1:
                return 404, "INSTANCE_NOT_FOUND"
            return 200, "OK"

        harness = _Harness(responder)
        self.addCleanup(harness.close)
        registrar = RoverRegistrar(
            nameserver_url=harness.url,
            token="test-token",
            service_name="order-service",
            instance_id="pod-one",
            host="127.0.0.1",
            port=8080,
            retry_interval_seconds=0.03,
            heartbeat_interval_seconds=0.03,
            request_timeout_seconds=0.2,
            logger=_quiet_logger(),
        )

        registrar.start()
        _wait_for(lambda: counts["register"] >= 3 and counts["heartbeat"] >= 2)

        register_requests = [r for r in harness.requests if r["operation"] == "register"]
        self.assertGreaterEqual(register_requests[1]["at"] - register_requests[0]["at"], 0.02)
        self.assertEqual(1, harness.max_active)
        self.assertTrue(
            all(r["authorization"] == "Bearer test-token" for r in harness.requests)
        )
        sessions = {r["body"]["sessionId"] for r in harness.requests}
        self.assertEqual(1, len(sessions))
        uuid.UUID(next(iter(sessions)))
        self.assertEqual("REGISTERED", registrar.state)

        registrar.close()
        self.assertEqual(1, counts["unregister"])
        self.assertEqual("CLOSED", registrar.state)

    def test_generic_heartbeat_404_is_permanent(self):
        counts = {"register": 0, "heartbeat": 0, "unregister": 0}

        def responder(operation):
            if operation == "register":
                counts[operation] += 1
                return 200, "OK"
            if operation == "heartbeat":
                counts[operation] += 1
                return 404, "NOT_FOUND"
            if operation == "unregister":
                counts[operation] += 1
            return 200, "OK"

        harness = _Harness(responder)
        self.addCleanup(harness.close)
        registrar = RoverRegistrar(
            nameserver_url=harness.url,
            service_name="order-service",
            instance_id="pod-one",
            host="127.0.0.1",
            port=8080,
            retry_interval_seconds=0.02,
            heartbeat_interval_seconds=0.02,
            request_timeout_seconds=0.2,
            logger=_quiet_logger(),
        )

        registrar.start()
        _wait_for(lambda: registrar.state == "FAILED")
        time.sleep(0.08)
        self.assertEqual(1, counts["register"])
        self.assertEqual(1, counts["heartbeat"])

        registrar.close()
        self.assertEqual(1, counts["unregister"])
        self.assertEqual("CLOSED", registrar.state)


class _Harness:
    def __init__(self, responder):
        self.responder = responder
        self.requests = []
        self.active = 0
        self.max_active = 0
        self.lock = threading.Lock()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        self.server.harness = self
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = "http://127.0.0.1:%d" % self.server.server_address[1]

    def handle(self, handler):
        with self.lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
        try:
            length = int(handler.headers.get("Content-Length", "0"))
            body = json.loads(handler.rfile.read(length).decode("utf-8"))
            operation = handler.path.rsplit("/", 1)[-1]
            entry = {
                "operation": operation,
                "body": body,
                "authorization": handler.headers.get("Authorization", ""),
                "at": time.monotonic(),
            }
            with self.lock:
                self.requests.append(entry)
            time.sleep(0.005)
            status, code = self.responder(operation)
            content = json.dumps(
                {"code": code, "message": code, "epoch": "test-epoch"}
            ).encode("utf-8")
            handler.send_response(status)
            handler.send_header("Content-Type", "application/json")
            handler.send_header("Content-Length", str(len(content)))
            handler.end_headers()
            handler.wfile.write(content)
        finally:
            with self.lock:
                self.active -= 1

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=1)


class _Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        self.server.harness.handle(self)

    def log_message(self, _format, *args):
        pass


def _quiet_logger():
    logger = logging.getLogger("rover-registrar-test-%s" % uuid.uuid4())
    logger.disabled = True
    return logger


def _wait_for(predicate, timeout=1.5):
    deadline = time.monotonic() + timeout
    while not predicate():
        if time.monotonic() >= deadline:
            raise AssertionError("condition was not met before timeout")
        time.sleep(0.005)


if __name__ == "__main__":
    unittest.main()
