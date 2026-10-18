#!/usr/bin/env python3
"""S0-06: isolated real Java processes, fixed workloads, alert replay and receipt probes.

Python 3.10+ standard library only. Build the four executable JARs first; see
docs-public/agent-fault-replay.zh-CN.md. Never connects to an existing deployment.
"""

import argparse
import concurrent.futures
import hashlib
import http.cookiejar
import json
import math
import os
from pathlib import Path
import random
import shutil
import socket
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
SCENARIOS = ROOT / "rover-gateway-test/replay/scenarios.json"
JARS = {
    "nameserver": "rover-nameserver-bootstrap/target/rover-nameserver-bootstrap-1.0.0-SNAPSHOT.jar",
    "gateway": "rover-gateway-bootstrap/target/rover-gateway-bootstrap-1.0.0-SNAPSHOT.jar",
    "backend": "rover-gateway-test/backend/target/rover-demo-1.0.0-SNAPSHOT.jar",
    "admin": "rover-admin/target/rover-admin-1.0.0-SNAPSHOT.jar",
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


class Http:
    def __init__(self, cookies=False):
        handlers = [urllib.request.ProxyHandler({})]
        if cookies:
            handlers.append(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.opener = urllib.request.build_opener(*handlers)

    def request(self, url, data=None, headers=None, allowed=(200,), timeout=5):
        require(urllib.parse.urlsplit(url).hostname == "127.0.0.1", "Only fixture loopback URLs are allowed")
        headers = dict(headers or {})
        if data is not None and not isinstance(data, bytes):
            data = json.dumps(data).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, headers=headers)
        try:
            response = self.opener.open(request, timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            code = response.code
            text = response.read().decode("utf-8", errors="replace")
            result_headers = dict(response.headers)
        require(code in allowed, f"{request.get_method()} {urllib.parse.urlsplit(url).path}: HTTP {code}")
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            payload = text
        return code, payload, result_headers


def summarize(rows):
    require(bool(rows), "No traffic samples: recovery cannot be asserted")
    counts, versions = {}, {}
    for row in rows:
        key = str(row["status"])
        counts[key] = counts.get(key, 0) + 1
        version = row["responseVersion"] or "unknown"
        versions[version] = versions.get(version, 0) + 1
    times = sorted(row["durationMillis"] for row in rows)
    failures = sum(row["status"] != 200 for row in rows)
    return {
        "requests": len(rows), "failures": failures, "errorRate": failures / len(rows),
        "statuses": counts, "responseVersions": versions,
        "p95Millis": times[math.ceil(len(times) * .95) - 1],
        "fromMillis": min(row["startMillis"] for row in rows),
        "toMillis": max(row["endMillis"] for row in rows),
    }


def signature(rows):
    """Compare outcomes of the SAME sticky users, independently of timing/scheduling."""
    return sorted((row["key"], row["status"], row["responseVersion"]) for row in rows)


def validate_fixture(fixture):
    require(fixture.get("schemaVersion") == 1, "Unsupported replay schema")
    require(fixture.get("path") == "/api/replay/order" and fixture.get("routeId") == "replay-orders",
            "Fixture must use the dedicated replay route")
    for name, low, high in (("samplesPerPhase", 40, 1000), ("concurrency", 1, 16), ("repetitions", 1, 10)):
        require(type(fixture.get(name)) is int and low <= fixture[name] <= high, f"Invalid {name}")
    require(type(fixture.get("seed")) is int, "A fixed integer workload seed is required")
    cases = fixture.get("scenarios", [])
    require(isinstance(cases, list) and 1 <= len(cases) <= 8, "Need 1-8 scenarios")
    ids = set()
    for case in cases:
        require(case.get("id") in ("bad-version", "upstream-timeout") and case["id"] not in ids,
                "Unknown or duplicate scenario")
        ids.add(case["id"])
        expected = ({"status": 503, "delayMillis": 0}, 503, "H5") if case["id"] == "bad-version" else (
            {"status": 200, "delayMillis": 1000}, 504, None)
        require((case.get("fault"), case.get("expectedFailureStatus"), case.get("confirmedHypothesis")) == expected,
                "Scenario truth must match the supported fault")


class Replay:
    def __init__(self, args, fixture):
        self.args, self.fixture = args, fixture
        self.output = args.output.resolve()
        # Never reuse a directory containing overlays, old credentials or old results.
        self.output.mkdir(parents=True, exist_ok=False)
        self.processes, self.logs = {}, []
        self.generation = {}
        self.http, self.admin = Http(), Http(cookies=True)
        self.token = uuid.uuid4().hex
        self.headers = {"X-Rover-Admin-Token": self.token}
        self.fault_headers = {"X-Rover-Replay-Token": self.token}
        self.urls = {name: f"http://127.0.0.1:{args.base_port + offset}" for name, offset in
                     (("nameserver", 1), ("gateway", 2), ("v1", 3), ("v2", 4), ("admin", 5))}
        rng = random.Random(fixture["seed"])
        self.keys = [f"user-{rng.getrandbits(128):032x}" for _ in range(fixture["samplesPerPhase"])]
        self.report = {"schemaVersion": 1, "stage": "S0-06", "faultReplayPassed": False,
                       "receiptProductionGatePassed": False, "cases": [], "processes": [],
                       "modelMode": "none", "mitigationActor": "test-harness", "error": None}

    def event(self, message):
        print(time.strftime("%H:%M:%S") + " " + message, flush=True)

    def component_dir(self, name):
        return self.output / name

    def configure(self):
        for port in range(self.args.base_port, self.args.base_port + 6):
            with socket.socket() as probe:
                # On Windows SO_REUSEADDR could hide a port collision; never set it.
                probe.bind(("127.0.0.1", port))
        for name, relative in JARS.items():
            require((ROOT / relative).is_file(), f"Build {name} JAR first: {relative}")
        manifest = {"fixture": self.fixture, "urls": self.urls, "keys": self.keys,
                    "python": sys.version, "jars": {}}
        for name, relative in JARS.items():
            manifest["jars"][name] = {"path": relative, "sha256": hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()}
        git = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True)
        manifest["sourceCommit"] = git.stdout.strip() if git.returncode == 0 else "unavailable"
        dirty = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT, capture_output=True, text=True)
        manifest["sourceDirty"] = bool(dirty.stdout.strip()) if dirty.returncode == 0 else None
        manifest["runnerSha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        manifest["fixtureSha256"] = hashlib.sha256(self.args.fixture.read_bytes()).hexdigest()
        save(self.output / "manifest.json", manifest)
        ns = {"port": self.args.base_port, "managePort": self.args.base_port + 1,
              "bindHost": "127.0.0.1", "manageBindHost": "127.0.0.1", "adminToken": self.token,
              "token": self.token, "healthCheckIntervalMillis": 1000}
        save(self.component_dir("nameserver") / "config/rover-nameserver.yml", {"rover": {"nameserver": ns}})
        gateway = {"port": self.args.base_port + 2, "adminEnabled": True, "adminToken": self.token,
                   "server": {"bindHost": "127.0.0.1", "maxInflight": 64},
                   "proxy": {"requestTimeoutMillis": 250, "connectTimeoutMillis": 1000},
                   "trace": {"enabled": True, "sampleRate": 1.0, "slowThresholdMillis": 10},
                   "metrics": {"enabled": True, "windowSeconds": 300},
                   "filters": {"accessLog": False}, "retry": {"enabled": False},
                   "circuitBreaker": {"enabled": False}, "rateLimit": {"enabled": False},
                   "discovery": {"type": "nameserver", "nameserver": {
                       "address": f"127.0.0.1:{self.args.base_port}", "token": self.token, "reconcileIntervalMs": 1000}},
                   "routes": [{"id": self.fixture["routeId"], "businessPrefix": "/api/replay",
                               "stickyHeader": "X-Replay-Key", "stripPrefix": "",
                               "targets": [{"serviceName": "replay-service", "group": version, "weight": 50}
                                           for version in ("v1", "v2")]}]}
        save(self.component_dir("gateway") / "config/rover-gateway.yml", {"rover": {"gateway": gateway}})
        for version in ("v1", "v2"):
            backend = {"server": {"address": "127.0.0.1", "port": self.args.base_port + (3 if version == "v1" else 4)},
                       "spring": {"application": {"name": "replay-service"}},
                       "rover": {"nameserver": {"enabled": True, "address": f"127.0.0.1:{self.args.base_port}",
                           "host": "127.0.0.1", "serviceName": "replay-service", "group": version,
                           "token": self.token, "heartbeat-interval-ms": 1000},
                           "demo": {"replay": {"enabled": True, "token": self.token}}}}
            save(self.component_dir(version) / "config/application.yml", backend)

    def start(self, name):
        directory = self.component_dir(name)
        directory.mkdir(exist_ok=True)
        self.generation[name] = self.generation.get(name, 0) + 1
        log_path = directory / f"process-{self.generation[name]}.log"
        log = log_path.open("wb")
        self.logs.append(log)
        tmp = directory / "tmp"
        tmp.mkdir(exist_ok=True)
        # JDK Unix-domain socket paths have a short OS limit even on Windows.
        socket_dir = ROOT / ".tmp/nio"
        socket_dir.mkdir(parents=True, exist_ok=True)
        args = [self.args.java, "-XX:ActiveProcessorCount=2", "-Xms32m", f"-Xmx{getattr(self.args, 'heap_mb', 384)}m",
                f"-Djava.io.tmpdir={tmp}", f"-Djdk.net.unixdomain.tmpdir={socket_dir}", "-jar",
                str(ROOT / JARS["backend" if name in ("v1", "v2") else name])]
        if name == "admin":
            args += [f"--server.port={self.args.base_port + 5}", "--server.address=127.0.0.1",
                     f"--rover.admin.gateway-url={self.urls['gateway']}",
                     f"--rover.admin.nameserver-manage-url={self.urls['nameserver']}",
                     f"--rover.admin.admin-token={self.token}",
                     f"--rover.admin.log-store-path={directory / 'records'}",
                     f"--rover.admin.model.config-file={directory / 'config/model.properties'}",
                     f"--rover.admin.model.master-key-file={directory / 'config/master.key'}",
                     "--rover.admin.model.master-key=", "--rover.agent.alerts.owner-user-id=admin",
                     "--rover.admin.log-collect-interval-seconds=5", "--spring.ai.model.chat=none"]
        # Avoid inheriting real model credentials, remote config or Java agents into fixtures.
        env = {key: value for key, value in os.environ.items() if not key.upper().startswith(
            ("ROVER_", "SPRING_", "OPENAI_", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))}
        process = subprocess.Popen(args, cwd=directory, stdout=log, stderr=subprocess.STDOUT, env=env,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        self.processes[name] = process
        self.report["processes"].append({"name": name, "pid": process.pid, "log": str(log_path.relative_to(self.output))})
        self.event(f"started {name}, pid={process.pid}")

    def stop(self, name):
        process = self.processes.get(name)
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def wait(self, name, predicate):
        deadline = time.monotonic() + self.args.startup_timeout
        while time.monotonic() < deadline:
            process = self.processes[name]
            require(process.poll() is None, f"{name} exited ({process.returncode}); inspect its process log")
            try:
                if predicate():
                    return
            except (OSError, RuntimeError):
                pass
            time.sleep(.25)
        raise RuntimeError(f"Timed out waiting for {name}; inspect its process log")

    def manage(self, suffix, data=None, allowed=(200,)):
        return self.http.request(self.urls["gateway"] + "/_manage" + suffix, data, self.headers, allowed)[1]

    def fault(self, status=200, delay=0):
        self.http.request(self.urls["v2"] + "/__test/replay", {"status": status, "delayMillis": delay}, self.fault_headers)

    def traffic(self, directory, phase):
        def sample(item):
            index, key = item
            start = time.time_ns() // 1_000_000
            clock = time.monotonic_ns()
            try:
                code, _, headers = Http().request(self.urls["gateway"] + self.fixture["path"],
                        headers={"X-Replay-Key": key}, allowed=(200, 503, 504, 502))
                version = next((value for header, value in headers.items() if header.lower() == "x-rover-replay-version"), "")
                error = None
            except (OSError, RuntimeError) as ex:
                code, version, error = 0, "", type(ex).__name__
            return {"index": index, "key": key, "startMillis": start, "endMillis": time.time_ns() // 1_000_000,
                    "durationMillis": round((time.monotonic_ns() - clock) / 1_000_000, 3),
                    "status": code, "responseVersion": version, "transportError": error}
        with concurrent.futures.ThreadPoolExecutor(max_workers=self.fixture["concurrency"]) as workers:
            rows = list(workers.map(sample, enumerate(self.keys)))
        directory.mkdir(parents=True, exist_ok=True)
        with (directory / f"{phase}-requests.jsonl").open("w", encoding="utf-8") as stream:
            for row in rows:
                stream.write(json.dumps(row) + "\n")
        result = summarize(rows)
        save(directory / f"{phase}-summary.json", result)
        save(directory / f"{phase}-metrics.json", self.manage(f"/metrics/routes?routeId={self.fixture['routeId']}&range=60"))
        save(directory / f"{phase}-traces.json", self.manage("/traces?path=" + self.fixture["path"]))
        self.event(f"{directory.name}/{phase}: {result['statuses']}, versions={result['responseVersions']}")
        return rows, result

    def login(self):
        url = self.urls["admin"]
        self.event("logging in to isolated Admin")
        auth = self.admin.request(url + "/api/auth/status")[1]
        form = urllib.parse.urlencode({"username": "admin", "password": "admin",
                                      auth["csrfParameterName"]: auth["csrfToken"]}).encode()
        self.admin.request(url + "/login", form, {"Content-Type": "application/x-www-form-urlencoded"}, timeout=30)
        self.event("login form submitted")
        auth = self.admin.request(url + "/api/auth/status")[1]
        require(auth["authenticated"] and auth["username"] == "admin", "Fixture login failed")
        self.csrf = {auth["csrfHeaderName"]: auth["csrfToken"]}

    def alert(self, directory, case, window):
        url = self.urls["admin"]
        payload = {"path": self.fixture["path"], "message": "回放告警：" + case["id"],
                   "fromMillis": window["fromMillis"], "toMillis": window["toMillis"]}
        save(directory / "alert-input.json", payload)
        _, task, _ = self.admin.request(url + "/api/agent/events/ingest", payload, self.csrf, allowed=(202,))
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            task = self.admin.request(url + "/api/agent/tasks/" + task["taskId"])[1]
            if task["status"] not in ("PENDING", "RUNNING"):
                break
            time.sleep(.2)
        save(directory / "task.json", task)
        workspace = self.admin.request(url + "/api/agent/sessions/" + task["sessionId"] + "/workspace")[1]
        save(directory / "workspace.json", workspace)
        require(task["status"] == "COMPLETED", "Alert investigation did not complete")
        require(workspace["session"]["userId"] == "admin" and workspace["activeIncident"]["triggeredBy"] == "admin",
                "Alert source or handler was lost")
        findings = task["result"]["hypotheses"]
        require(not any(item["status"] == "REJECTED" for item in findings), "Current state incorrectly refuted a historical fault")
        if case["confirmedHypothesis"]:
            require(any(item["id"] == case["confirmedHypothesis"] and item["status"] == "CONFIRMED" for item in findings),
                    "Observed in-window 503 was not confirmed")
        evidence = task["result"]["evidence"]
        require(bool(evidence), "No investigation evidence")
        require(all(item["metadata"].get("requestedFromMillis") == str(window["fromMillis"])
                    and item["metadata"].get("requestedToMillis") == str(window["toMillis"]) for item in evidence),
                "Evidence window differs from replay window")
        require(any(item["type"] == "TRACE" and item["metadata"].get("temporalScope") == "REQUESTED_WINDOW"
                    and int(item["metadata"].get("sampleSize", 0)) > 0 for item in evidence), "No real trace samples reached the agent")
        return {"taskId": task["taskId"], "sessionId": task["sessionId"], "status": task["status"]}

    def weight(self, value, operation_id=None, revision=None, allowed=(200,)):
        if revision is None:
            revision = self.manage("/routes")["revision"]
        body = {"routeId": self.fixture["routeId"], "serviceName": "replay-service", "group": "v2",
                "weight": value, "revision": revision, "operationId": operation_id or uuid.uuid4().hex}
        status, result, _ = self.http.request(self.urls["gateway"] + "/_manage/routes/targets/weight",
                                             body, self.headers, allowed)
        if operation_id is None:
            require(result.get("status") == "APPLIED", "Fresh fixture operation was not applied")
        return {"request": body, "response": result, "httpStatus": status}

    def operation(self, operation_id):
        return self.manage("/routes/operations/" + operation_id)

    def scenario(self, case, repetition):
        directory = self.output / "cases" / f"{case['id']}-{repetition}"
        result = {"scenario": case["id"], "repetition": repetition, "passed": False}
        self.report["cases"].append(result)
        self.fault()
        baseline, result["baseline"] = self.traffic(directory, "baseline")
        require(result["baseline"]["failures"] == 0 and set(result["baseline"]["responseVersions"]) == {"v1", "v2"},
                "Baseline must serve both versions without errors")
        self.fault(case["fault"]["status"], case["fault"]["delayMillis"])
        failed, result["fault"] = self.traffic(directory, "fault")
        expected = {row["key"]: row["responseVersion"] for row in baseline}
        require(all(row["status"] == (case["expectedFailureStatus"] if expected[row["key"]] == "v2" else 200)
                    for row in failed), "Fault outcomes do not match the baseline's sticky version assignment")
        require(result["fault"]["failures"] > 0, "Injected fault did not affect real traffic")
        # Close trace completion before querying the exact client-observed window.
        time.sleep(.1)
        result["alert"] = self.alert(directory, case, result["fault"])
        result["mitigation"] = self.weight(0)
        require(result["mitigation"]["response"]["status"] == "APPLIED", "Fixture mitigation failed")
        mitigated, result["mitigated"] = self.traffic(directory, "mitigated")
        require(result["mitigated"]["failures"] == 0 and result["mitigated"]["responseVersions"] == {"v1": len(mitigated)},
                "Stopping v2 did not restore the sampled requests")
        # This is a TEST HARNESS operation, never presented as an autonomous agent action.
        self.fault()
        time.sleep(1.1 if case["fault"]["delayMillis"] else .05)
        result["restore"] = self.weight(50)
        restored, result["restored"] = self.traffic(directory, "restored")
        require(result["restored"]["failures"] == 0 and signature(restored) == signature(baseline),
                "Reset did not restore both versions for the same users")
        result["fingerprints"] = {phase: hashlib.sha256(json.dumps(signature(rows)).encode()).hexdigest()
                                  for phase, rows in (("baseline", baseline), ("fault", failed), ("mitigated", mitigated))}
        result["passed"] = True
        save(directory / "result.json", result)

    def receipts(self):
        self.event("probing operation receipts: replay parameters, restart, >200 operations")
        first = self.weight(51)
        operation_id = first["request"]["operationId"]
        second = self.weight(52)
        before = self.operation(operation_id)
        require(before["status"] == "APPLIED", "Fresh applied operation cannot be queried")
        mismatch = self.weight(53, operation_id, first["request"]["revision"], (200, 409))
        current = self.manage("/routes")
        require(current["revision"] == second["response"]["revision"], "Parameter replay unexpectedly changed the gateway")
        self.stop("gateway")
        self.start("gateway")
        self.wait("gateway", lambda: self.manage("/health") is not None)
        restored = self.manage("/routes")
        require(restored["revision"] == current["revision"] and restored["routes"] == current["routes"],
                "Gateway routing state did not survive restart")
        after = {"first": self.operation(operation_id), "latest": self.operation(second["request"]["operationId"])}
        marker = self.weight(51)
        operations = []
        for index in range(205):
            operations.append(self.weight(52 + index % 2))
        evicted = self.operation(marker["request"]["operationId"])
        newest = self.operation(operations[-1]["request"]["operationId"])
        require(newest["status"] == "APPLIED", "Newest receipt is missing")
        self.weight(50)
        def matches(receipt, applied):
            return receipt.get("status") == "APPLIED" and receipt.get("revision") == applied["response"]["revision"]
        checks = {"parameterMismatchRejected": mismatch["httpStatus"] == 409,
                  "firstReceiptSurvivesRestart": matches(after["first"], first),
                  "latestReceiptSurvivesRestart": matches(after["latest"], second),
                  "receiptSurvivesMoreThan200Operations": matches(evicted, marker)}
        evidence = {"first": first, "second": second, "firstQueryBeforeRestart": before,
                    "sameIdDifferentParameters": mismatch, "routingRevisionAfterRestart": restored["revision"],
                    "queriesAfterRestart": after, "evictionMarker": marker,
                    "followingOperations": operations, "oldQueryAfter205Operations": evicted,
                    "newestQuery": newest, "productionChecks": checks}
        save(self.output / "receipt-probe.json", evidence)
        self.report["receiptProductionChecks"] = checks
        self.report["receiptProductionGatePassed"] = all(checks.values())

    def run(self):
        self.configure()
        self.start("nameserver")
        self.wait("nameserver", lambda: self.http.request(self.urls["nameserver"] + "/_manage/health", headers=self.headers))
        for version in ("v1", "v2"):
            self.start(version)
        for version in ("v1", "v2"):
            self.wait(version, lambda version=version: self.http.request(self.urls[version] + "/__test/replay", headers=self.fault_headers))
            require(self.http.request(self.urls[version] + "/__test/replay", allowed=(403,))[0] == 403,
                    "Fault control is accessible without its fixture token")
        self.start("gateway")
        self.wait("gateway", lambda: self.manage("/health") is not None)
        require(self.http.request(self.urls["gateway"] + "/__test/replay", allowed=(404,))[0] == 404,
                "Fault control must not be exposed through the business gateway")
        self.report["faultControlProtected"] = True
        def registered():
            rows = self.http.request(self.urls["nameserver"] + "/_manage/instances", headers=self.headers)[1]
            return {row.get("group") for row in rows if row.get("serviceName") == "replay-service" and row.get("healthy")} == {"v1", "v2"}
        self.wait("nameserver", registered)
        self.start("admin")
        self.wait("admin", lambda: self.admin.request(self.urls["admin"] + "/api/auth/status"))
        self.login()
        # Discovery has to reach the gateway, not just the registry.
        self.wait("gateway", lambda: self.http.request(self.urls["gateway"] + self.fixture["path"], headers={"X-Replay-Key": self.keys[0]}))
        for case in self.fixture["scenarios"]:
            for repetition in range(1, self.fixture["repetitions"] + 1):
                self.scenario(case, repetition)
        self.report["repeatable"] = self.fixture["repetitions"] >= 2 and all(
            len({json.dumps(row["fingerprints"], sort_keys=True) for row in self.report["cases"] if row["scenario"] == case["id"]}) == 1
            for case in self.fixture["scenarios"])
        require(self.report["repeatable"], "At least two repetitions must produce matching per-user outcomes")
        self.report["faultReplayPassed"] = True
        self.receipts()
        if getattr(self.args, "require_single_admin_foundation", False):
            self.single_admin_foundation()

    def single_admin_foundation(self):
        """Exercise actual HTTP filters, durable events and Admin restart in this owned fixture."""
        self.event("checking single Admin identity, permissions, restart, event replay and backup")
        base = self.urls["admin"]
        checks = {}
        assets = self.admin.request(base + "/api/agent/assets")[1]
        checks["resourceInventory"] = bool(assets["identityComplete"] and assets["resources"])
        user_name, password = "fixture-operator", "fixture-" + uuid.uuid4().hex
        self.admin.request(base + "/api/agent/security/users", {
            "username": user_name, "password": password, "roles": ["OPERATOR"], "enabled": True}, self.csrf)
        operator = Http(cookies=True)
        auth = operator.request(base + "/api/auth/status")[1]
        operator.request(base + "/login", urllib.parse.urlencode({"username": user_name, "password": password,
            auth["csrfParameterName"]: auth["csrfToken"]}).encode(), {"Content-Type": "application/x-www-form-urlencoded"})
        auth = operator.request(base + "/api/auth/status")[1]
        require(auth["authenticated"], "Fixture operator login failed")
        operator_csrf = {auth["csrfHeaderName"]: auth["csrfToken"]}
        checks["operatorCannotApprove"] = operator.request(base + "/api/agent/actions/fake/approve", {}, operator_csrf, allowed=(403,))[0] == 403
        checks["operatorCannotBypassAgent"] = operator.request(base + "/api/configs", {}, operator_csrf, allowed=(403,))[0] == 403
        issued = self.admin.request(base + "/api/agent/security/connectors", {"name": "fixture-monitor"}, self.csrf)[1]
        bearer = {"Authorization": "Bearer " + issued["token"]}
        machine = Http()
        checks["machineCannotRead"] = machine.request(base + "/api/agent/security/me", headers=bearer, allowed=(403,))[0] == 403
        task = machine.request(base + "/api/agent/events/ingest", {"path": self.fixture["path"], "message": "fixture machine alert"}, bearer, allowed=(202,))[1]
        deadline = time.monotonic() + 45
        while task["status"] in ("PENDING", "RUNNING") and time.monotonic() < deadline:
            time.sleep(.2)
            task = self.admin.request(base + "/api/agent/tasks/" + task["taskId"])[1]
        require(task["status"] == "COMPLETED", "Fixture machine alert did not complete")
        workspace = self.admin.request(base + "/api/agent/sessions/" + task["sessionId"] + "/workspace")[1]
        checks["machineSourceSeparateFromHandler"] = (workspace["session"]["userId"] == "admin"
            and workspace["activeIncident"]["triggeredBy"] == "connector:fixture-monitor")
        checks["privateSessionIsolated"] = operator.request(base + "/api/agent/tasks/" + task["taskId"], allowed=(404,))[0] == 404
        self.admin.request(base + "/api/agent/security/users", {"username": user_name, "roles": ["OPERATOR"], "enabled": False}, self.csrf)
        checks["liveUserRevocation"] = operator.request(base + "/api/agent/security/me", allowed=(403,))[0] == 403
        self.admin.request(base + "/api/agent/security/connectors/" + issued["id"] + "/revoke", {}, self.csrf)
        self.admin.request(base + "/api/agent/operations/write-stop", {"stopped": True}, self.csrf)
        checks["writeStopBlocksRawWrites"] = self.admin.request(base + "/api/configs", {}, self.csrf, allowed=(403,))[0] == 403
        self.stop("admin")
        self.admin = Http(cookies=True)
        self.start("admin")
        self.wait("admin", lambda: self.admin.request(base + "/api/auth/status"))
        self.login()
        state = self.admin.request(base + "/api/agent/operations")[1]
        checks["writeStopSurvivesAdminRestart"] = bool(state["healthy"] and state["writesStopped"])
        checks["tokenRevocationSurvivesAdminRestart"] = machine.request(base + "/api/agent/events/ingest",
            {"path": self.fixture["path"]}, bearer, allowed=(401,))[0] == 401
        stream = self.admin.request(base + "/api/agent/tasks/" + task["taskId"] + "/events", headers={"Last-Event-ID": "0"}, timeout=15)[1]
        checks["durableEventsAfterAdminRestart"] = "id:" in stream and "TASK_COMPLETED" in stream
        backup = self.admin.request(base + "/api/agent/operations/backups", {}, self.csrf, timeout=30)[1]
        backup_path = Path(backup["path"]).resolve()
        checks["consistentBackupCreated"] = backup_path.is_relative_to(self.output.resolve()) and backup_path.is_file() and backup_path.stat().st_size > 0
        audit = self.admin.request(base + "/api/agent/operations/audit")[1]
        kinds = {row["kind"] for row in audit}
        checks["securityAuditPersisted"] = {"ACCOUNT_CHANGED", "CONNECTOR_ISSUED", "CONNECTOR_REVOKED", "WRITE_STOP_CHANGED"} <= kinds
        self.admin.request(base + "/api/agent/operations/write-stop", {"stopped": False}, self.csrf)
        evidence = {"checks": checks, "inventory": assets, "taskId": task["taskId"], "sessionId": task["sessionId"],
                    "backupId": backup["backupId"], "auditKinds": sorted(kinds)}
        save(self.output / "single-admin-probe.json", evidence)
        self.report["singleAdminChecks"] = checks
        self.report["singleAdminGatePassed"] = all(checks.values())
        require(all(checks.values()), "At least one single Admin foundation check failed")

    def close(self):
        errors = []
        for name in reversed(list(self.processes)):
            try:
                self.stop(name)
            except (OSError, subprocess.TimeoutExpired) as error:
                errors.append(f"{name}: {type(error).__name__}")
        for log in self.logs:
            log.close()
        self.report["cleanupPassed"] = not errors and all(p.poll() is not None for p in self.processes.values())
        self.report["cleanupErrors"] = errors
        save(self.output / "result.json", self.report)
        lines = ["# Agent fault replay", "", f"Fault replay: {self.report['faultReplayPassed']}",
                 f"Cleanup: {self.report['cleanupPassed']}",
                 f"Receipt production gate: {self.report['receiptProductionGatePassed']}", "",
                 "Mitigation actor: test-harness; model mode: none.", "",
                 "| Scenario | Run | Baseline errors | Fault errors | Mitigated errors | Passed |",
                 "| --- | --- | --- | --- | --- | --- |"]
        for row in self.report["cases"]:
            counts = [str(row.get(phase, {}).get("failures", "incomplete")) for phase in ("baseline", "fault", "mitigated")]
            lines.append(f"| {row['scenario']} | {row['repetition']} | {' | '.join(counts)} | {row['passed']} |")
        lines += ["", "Receipt checks: " + json.dumps(self.report.get("receiptProductionChecks", {})),
                  "", "Error: " + str(self.report["error"]), "",
                  "Raw evidence: cases/*/*-requests.jsonl, task.json, workspace.json, receipt-probe.json.",
                  "Metrics are rolling snapshots; phase error rates use actual client request outcomes.",
                  "This run does not certify capacity, LLM quality, automatic remediation or production readiness."]
        (self.output / "SUMMARY.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--fixture", type=Path, default=SCENARIOS)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/agent-fault-replay" / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]))
    parser.add_argument("--base-port", type=int, default=19080, help="Six consecutive localhost ports")
    parser.add_argument("--startup-timeout", type=int, default=90)
    parser.add_argument("--heap-mb", type=int, default=384, help="Heap cap per fixture JVM (128..512)")
    parser.add_argument("--require-durable-receipts", action="store_true", help="Fail if the S1-05 receipt production gate is not met")
    parser.add_argument("--require-single-admin-foundation", action="store_true", help="Also verify identity, roles, machine ingress, Admin restart, SSE and backup over real HTTP")
    args = parser.parse_args()
    parser.error("Java 17+ must be on PATH or supplied with --java") if not args.java else None
    require(1024 <= args.base_port <= 65530 and 1 <= args.startup_timeout <= 300, "Invalid port/timeout")
    require(128 <= args.heap_mb <= 512, "Invalid fixture heap cap")
    fixture = json.loads(args.fixture.read_text(encoding="utf-8"))
    validate_fixture(fixture)
    replay = Replay(args, fixture)
    code = 0
    try:
        replay.run()
        if args.require_durable_receipts and not replay.report["receiptProductionGatePassed"]:
            code = 2
    except KeyboardInterrupt:
        replay.report["error"], code = "Interrupted", 130
    except Exception as error:
        replay.report["error"], code = str(error), 1
        replay.event("FAILED: " + str(error))
        traceback.print_exc()
    finally:
        replay.close()
    if not replay.report["cleanupPassed"]:
        code = 1
    print(f"Results: {replay.output / 'SUMMARY.md'}", flush=True)
    print(f"Replay={replay.report['faultReplayPassed']}; receipt production gate={replay.report['receiptProductionGatePassed']}", flush=True)
    return code


if __name__ == "__main__":
    sys.exit(main())
