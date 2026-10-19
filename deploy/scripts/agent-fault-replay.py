#!/usr/bin/env python3
"""S0-06: isolated real Java processes, fixed workloads, alert replay and receipt probes.

Python 3.10+ standard library only. Build the four executable JARs first; see
docs-public/agent-fault-replay.zh-CN.md. Never connects to an existing deployment.
"""

import argparse
import concurrent.futures
import hashlib
import http.cookiejar
import http.server
import json
import math
import os
from pathlib import Path
import random
import re
import shutil
import socket
import subprocess
import sys
import threading
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


def validate_evidence_contract(evidence, expected_status, route_id, v2_host_port):
    require(all(item.get("envelope", {}).get("schemaVersion") == 1 for item in evidence),
            "An evidence item lost its versioned envelope")
    traces = [item for item in evidence if item["type"] == "TRACE"]
    expected_origin = "UPSTREAM" if expected_status == 503 else "GATEWAY"
    expected_phase = "NONE" if expected_status == 503 else "UPSTREAM_TIMEOUT"
    observed = []
    calculated = []
    for item in traces:
        envelope = item["envelope"]
        require(envelope["qualityStatus"] == "PARTIAL", "Sampled traces falsely claim complete coverage")
        context = envelope.get("context") or {}
        require(context.get("scope") and context.get("controlTargetId") and context.get("taskId") == item["taskId"],
                "Trace lost its server-owned investigation context")
        for population in envelope.get("facts", {}).get("samplePopulations", []):
            if population.get("group") == "v2" and population.get("hostPort") == v2_host_port:
                require(population["sampleRequests"] > 0 and population["sample5xx"] == population["sampleRequests"]
                        and population["sample5xxRate"] == 1, "Program-computed v2 sample denominator is wrong")
                calculated.append(population)
        for row in envelope.get("facts", {}).get("rows", []):
            if row["statusCode"] != expected_status:
                continue
            require(row.get("routeId") == route_id and row.get("group") == "v2"
                    and row.get("hostPort") == v2_host_port, "Fault was attributed to the wrong resource")
            require(row.get("responseOrigin") == expected_origin and row.get("failurePhase") == expected_phase,
                    "HTTP status was mistaken for response origin or failure phase")
            require(row.get("resourceUid") and row.get("generation", 0) > 0, "Captured route identity is missing")
            require(any(ref["resourceUid"] == row["resourceUid"] and ref["generation"] == row["generation"]
                        and ref["scope"] == context["scope"] for ref in envelope.get("resourceRefs", [])),
                    "Captured identity was not bound to the trusted scope")
            require(len(row.get("attempts", [])) == 1, "Fixture retry is disabled; attempt accounting differs")
            observed.append(row)
    require(observed, "No captured v2 failure fact reached the agent")
    require(calculated, "Program-computed sample populations did not reach the agent")
    current = [item for item in evidence if item["type"] in ("ROUTE", "INSTANCE", "CONFIG")]
    require(all(item["envelope"]["qualityStatus"] == "OUT_OF_RANGE" for item in current),
            "Current state was used as historical coverage")
    return {"capturedFailureSamples": len(observed), "responseOrigin": expected_origin, "failurePhase": expected_phase,
            "resourceIdentityAndScope": True, "currentStateIsHistoricalProof": False,
            "v2SampleRateComputedByProgram": True}


def validate_hypothesis_contract(task):
    findings = task["result"]["hypotheses"]
    ids = {item["evidenceId"] for item in task["result"]["evidence"]}
    require(findings, "No structured hypothesis assessment")
    for item in findings:
        support, counter = item.get("supportingEvidenceIds", []), item.get("counterEvidenceIds", [])
        require(set(support + counter) <= ids, "Hypothesis contains forged or missing evidence references")
        if item["status"] == "CONFIRMED":
            require(support, "Confirmed hypothesis has no supporting evidence")
        elif item["status"] == "REJECTED":
            require(counter, "Rejected hypothesis has no counter evidence")
        else:
            require(item.get("missingEvidence"), "Unknown hypothesis lost its evidence gap")
    progress = task["plan"]["progress"]
    require(progress.get("stopReason"), "Investigation stopped without a recorded reason")
    attempts = progress["attempts"]
    require(attempts and progress["toolCalls"] <= 10 and progress["rounds"] <= 3, "Investigation budget exceeded")
    counts = {a["capability"]: sum(b["capability"] == a["capability"] for b in attempts) for a in attempts}
    require(all(count <= 2 for count in counts.values()), "Repeated capability exceeded the one-retry limit")
    require(all(not item["id"].startswith("M") or item["status"] == "UNKNOWN" for item in findings),
            "Model candidate was promoted to a verified conclusion")
    return {"actualEvidenceReferences": True, "unknownRetainsGaps": True, "modelCannotConfirm": True,
            "boundedAttempts": counts, "stopReason": progress["stopReason"]}


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


def validate_live_analysis(task):
    """A successful rule fallback must never count as a successful live-model test."""
    report = task.get("result") or {}
    steps = [step for step in task.get("steps", []) if step.get("type") == "AI_EXPLANATION"]
    require(task.get("status") == "COMPLETED" and bool((report.get("aiAnalysis") or "").strip())
            and any(step.get("status") == "COMPLETED" for step in steps)
            and not any(step.get("status") == "FAILED" for step in steps),
            "Live model did not finish its explanation; rule fallback is not a live-model pass")
    return {"analysis": report["aiAnalysis"], "steps": steps,
            "evidenceCount": len(report.get("evidence", [])),
            "executedCapabilities": task.get("executedCapabilities", [])}


def validate_change_timeline(evidence, expected, window):
    timelines = [item["envelope"] for item in evidence
                 if item.get("envelope", {}).get("sourceId") == "rover.change-timeline"]
    require(bool(timelines), "Investigation did not collect the durable change timeline")
    for envelope in timelines:
        require(envelope.get("qualityStatus") == "PARTIAL" and envelope.get("coverageRange") is None,
                "Polling/push timeline claimed complete coverage")
        facts = envelope.get("facts", {})
        requested = facts.get("investigationRange", {})
        actual = facts.get("queryRange", {})
        require(requested == {"fromMillis": window["fromMillis"], "toMillis": window["toMillis"]},
                "Timeline lost the incident window")
        require(0 <= requested["fromMillis"] - actual.get("fromMillis", -1) <= 900_000
                and actual.get("toMillis") == requested["toMillis"], "Timeline lookback was not bounded or explicit")
        require(facts.get("causalityConfirmed") is False, "Temporal correlation was treated as confirmed causality")
        for event in facts.get("events", []):
            if event.get("eventId") != expected["eventId"]:
                continue
            require(event.get("sourceEventId") == expected["sourceEventId"] and event.get("sourceId") == expected["sourceId"]
                    and event.get("facts", {}).get("releaseId") == expected["facts"]["releaseId"]
                    and event.get("facts", {}).get("artifact") == expected["facts"]["artifact"]
                    and event.get("scope") == envelope["context"]["scope"]
                    and event.get("controlTargetId") == envelope["context"]["controlTargetId"]
                    and event.get("subject", {}).get("group") == "v2", "Release evidence lost its identity or trusted scope")
            require(actual["fromMillis"] <= event["occurredAtMillis"] <= actual["toMillis"],
                    "Release lies outside the documented source query")
            require(event["occurredAtMillis"] < window["fromMillis"], "Fixture did not verify a pre-incident release")
            return {"releaseId": expected["facts"]["releaseId"], "eventId": expected["eventId"],
                    "programBoundedLookback": True, "preIncidentReleaseAssociated": True,
                    "qualityPartial": True, "causalityNotAsserted": True}
    raise RuntimeError("The pre-incident release was not associated with this route's historical service")


class Replay:
    def __init__(self, args, fixture):
        self.args, self.fixture = args, fixture
        self.output = args.output.resolve()
        # Never reuse a directory containing overlays, old credentials or old results.
        self.output.mkdir(parents=True, exist_ok=False)
        self.processes, self.logs = {}, []
        self.generation = {}
        self.live_model = getattr(args, "model_config", None) is not None
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
                       "modelMode": "live" if self.live_model else "none",
                       "mitigationActor": "test-harness", "error": None}
        if self.live_model:
            self.report["liveModelGatePassed"] = False
        if getattr(args, "require_change_timeline", False):
            self.report["changeTimelineGatePassed"] = False
        if getattr(args, "require_hypothesis_contract", False):
            self.report["hypothesisContractGatePassed"] = False

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
        if self.live_model:
            config = self.args.model_config.resolve()
            key = (self.args.model_master_key_file or config.with_name("master.key")).resolve()
            require(config.is_file() and key.is_file(), "Live model needs an existing encrypted config and master-key file")
            private = self.component_dir("admin") / "config"
            private.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(config, private / "model.properties")
            shutil.copyfile(key, private / "master.key")

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
            if self.live_model:
                # ModelConfigStore reads the explicit encrypted FILE config; none disables auto-configuration only.
                args += ["--management.metrics.export.prometheus.enabled=true",
                         "--management.endpoints.web.exposure.include=health,info,prometheus"]
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

    def verify_model(self):
        base = self.urls["admin"]
        current = self.admin.request(base + "/api/model/config")[1]
        public = {key: current.get(key) for key in ("enabled", "model", "baseUrl", "timeoutSeconds",
                  "configured", "available", "applied", "apiKeyReadable", "masterKeyState", "fastModel")}
        save(self.output / "live-model-config.json", public)
        require(all(current.get(key) for key in ("enabled", "configured", "available", "applied", "apiKeyReadable")),
                "Live model config is disabled, unreadable or unavailable")
        self.event("verifying actual model connection: " + str(current["model"]))
        result = self.admin.request(base + "/api/model/verify", {}, self.csrf, timeout=120)[1]
        save(self.output / "live-model-connection.json", result)
        require(result.get("ok") is True, "Actual model connection failed: " + str(result.get("errorCode")))
        self.report["liveModel"] = public
        self.event("actual model connection verified")

    def timeline_query(self, from_millis, to_millis):
        query = urllib.parse.urlencode({"targetType": "ROUTE", "target": self.fixture["path"],
                                      "fromMillis": from_millis, "toMillis": to_millis, "limit": 200})
        return self.admin.request(self.urls["admin"] + "/api/agent/timeline?" + query)[1]

    def setup_change_timeline(self):
        self.event("waiting for durable route observations and issuing a release-only fixture identity")
        issued = self.admin.request(self.urls["admin"] + "/api/agent/security/connectors",
                                   {"name": "fixture-ci", "purpose": "RELEASE"}, self.csrf)[1]
        self.timeline_bearer = {"Authorization": "Bearer " + issued["token"]}
        def ready():
            now = time.time_ns() // 1_000_000
            result = self.timeline_query(now - 60_000, now)
            healthy_sources = {row["sourceId"] for row in result.get("sources", []) if row.get("available") and row.get("lastSuccessAtMillis", 0) > 0}
            return {"rover.routes", "rover.instances", "rover.config.gateway", "rover.config.nameserver"} <= healthy_sources \
                and any(row["kind"] == "ROUTE_BASELINE" and row["subject"]["routeId"] == self.fixture["routeId"]
                        for row in result.get("events", []))
        self.wait("admin", ready)

    def publish_fixture_release(self, directory, case, repetition):
        now = time.time_ns() // 1_000_000
        request = {"sourceEventId": uuid.uuid4().hex, "releaseId": f"fixture-{case['id']}-{repetition}",
                   "artifact": f"fixture:{case['id']}:{repetition}", "serviceName": "replay-service", "group": "v2",
                   "status": "SUCCEEDED", "occurredAtMillis": now}
        machine = Http()
        _, accepted, _ = machine.request(self.urls["admin"] + "/api/agent/releases/ingest", request,
                                         self.timeline_bearer, allowed=(201,))
        _, replayed, _ = machine.request(self.urls["admin"] + "/api/agent/releases/ingest", request,
                                         self.timeline_bearer, allowed=(200,))
        require(replayed["duplicate"] and replayed["event"] == accepted["event"], "Release replay changed its durable facts")
        require(machine.request(self.urls["admin"] + "/api/agent/events/ingest", {"path": self.fixture["path"]},
                                self.timeline_bearer, allowed=(403,))[0] == 403, "Release token could submit alerts")
        self.timeline_release = accepted["event"]
        save(directory / "release-event.json", {"request": request, "accepted": accepted, "replayed": replayed})

    def verify_timeline_mitigation(self, directory, since):
        evidence = {}
        def observed():
            now = time.time_ns() // 1_000_000
            result = self.timeline_query(since, now)
            evidence.update(result)
            for event in result.get("events", []):
                after = event.get("facts", {}).get("after") or {}
                if event["kind"] == "ROUTE_CHANGED" and event["subject"].get("routeId") == self.fixture["routeId"] \
                        and event["observedAtMillis"] >= since \
                        and any(t.get("group") == "v2" and t.get("weight") == 0 for t in after.get("targets", [])):
                    return True
            return False
        self.wait("admin", observed)
        save(directory / "timeline-after-mitigation.json", evidence)

    def model_metrics(self, directory, label):
        scrape = self.admin.request(self.urls["admin"] + "/actuator/prometheus")[1]
        require(isinstance(scrape, str), "Prometheus model evidence is unavailable")
        lines = [line for line in scrape.splitlines() if line.startswith("rover_agent_")]
        directory.mkdir(parents=True, exist_ok=True)
        (directory / (label + "-agent-metrics.prom")).write_text("\n".join(lines) + "\n", encoding="utf-8")
        counts = {}
        for line in lines:
            match = re.fullmatch(r'rover_agent_model_calls_total\{([^}]+)\}\s+([0-9.eE+\-]+)', line)
            if match:
                labels = dict(re.findall(r'(\w+)="([^"]*)"', match[1]))
                key = labels.get("scene", "") + ":" + labels.get("outcome", "")
                counts[key] = counts.get(key, 0) + float(match[2])
        return counts

    def wait_task(self, task, seconds, label):
        deadline, next_progress = time.monotonic() + seconds, time.monotonic() + 15
        while time.monotonic() < deadline:
            task = self.admin.request(self.urls["admin"] + "/api/agent/tasks/" + task["taskId"])[1]
            if task["status"] not in ("PENDING", "RUNNING"):
                break
            if time.monotonic() >= next_progress:
                self.event(f"{label}: {task['status']}/{task.get('currentStage')}")
                next_progress = time.monotonic() + 15
            time.sleep(.2)
        return task

    def live_conversation(self):
        directory = self.output / "conversation"
        before = self.model_metrics(directory, "before")
        revision = self.manage("/routes")["revision"]
        session = self.admin.request(self.urls["admin"] + "/api/agent/sessions",
                                    {"title": "live model read-only investigation"}, self.csrf)[1]
        question = {"message": "请使用只读工具检查 /api/replay/order 的路由、v1/v2 实例健康情况和错误率。"
                               "请引用实际证据，缺少窗口数据时说明无法确认，不要修改任何配置。",
                    "path": self.fixture["path"]}
        save(directory / "input.json", question)
        task = self.admin.request(self.urls["admin"] + "/api/agent/sessions/" + session["sessionId"] + "/messages",
                                  question, self.csrf, allowed=(202,))[1]
        task = self.wait_task(task, 240, "live model tool conversation")
        save(directory / "task.json", task)
        workspace = self.admin.request(self.urls["admin"] + "/api/agent/sessions/" + session["sessionId"] + "/workspace")[1]
        save(directory / "workspace.json", workspace)
        after = self.model_metrics(directory, "after")
        calls = {key: value - before.get(key, 0) for key, value in after.items()}
        evidence = (task.get("result") or {}).get("evidence", [])
        checks = {"completed": task["status"] == "COMPLETED", "actualModelCall": calls.get("对话:ok", 0) > 0,
                  "toolEvidence": bool(evidence), "routeUnchanged": revision == self.manage("/routes")["revision"],
                  "answerNonempty": bool((task.get("result") or {}).get("summary", "").strip())}
        save(directory / "checks.json", {"checks": checks, "calls": calls})
        self.report["conversationChecks"] = checks
        require(all(checks.values()), "Live model tool conversation failed")

    def model_failure_probe(self):
        """Fail a local provider after the real-model cases; never forward real credentials to it."""
        self.event("checking provider failure: preserve evidence and disclose rule fallback")
        directory = self.output / "model-failure"
        revision = self.manage("/routes")["revision"]

        class Unavailable(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", 0)))
                self.send_response(503)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"error":{"message":"fixture provider unavailable","type":"server_error"}}')

            def log_message(self, *args):
                pass

        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Unavailable) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                body = {"enabled": True, "vendor": "custom", "baseUrl": f"http://127.0.0.1:{server.server_port}",
                        "model": "fixture-unavailable", "apiKey": "fixture-not-a-provider-secret",
                        "timeoutSeconds": 3, "fastBaseUrl": "", "fastModel": ""}
                self.admin.request(self.urls["admin"] + "/api/model/config", body, self.csrf)
                task = self.admin.request(self.urls["admin"] + "/api/agent/events/ingest",
                    {"path": self.fixture["path"], "message": "检查模型供应商不可用时的调查收尾"},
                    self.csrf, allowed=(202,))[1]
                task = self.wait_task(task, 60, "provider failure")
                save(directory / "task.json", task)
                self.model_metrics(directory, "after")
                report = task.get("result") or {}
                checks = {"completed": task["status"] == "COMPLETED", "evidencePreserved": bool(report.get("evidence")),
                          "noInventedAnalysis": not report.get("aiAnalysis"),
                          "failureDisclosed": any(step.get("type") == "AI_EXPLANATION" and step.get("status") == "FAILED"
                                                  for step in task.get("steps", [])),
                          "routeUnchanged": revision == self.manage("/routes")["revision"]}
                save(directory / "checks.json", checks)
                self.report["modelFailureChecks"] = checks
                require(all(checks.values()), "Provider failure did not preserve evidence or disclose fallback")
            finally:
                server.shutdown()
                worker.join(timeout=5)

    def alert(self, directory, case, window):
        url = self.urls["admin"]
        message = "监控检测到请求失败，请按指定时间窗口核查原因，区分已知事实与待验证假设。" if self.live_model else "回放告警：" + case["id"]
        payload = {"path": self.fixture["path"], "message": message,
                   "fromMillis": window["fromMillis"], "toMillis": window["toMillis"]}
        save(directory / "alert-input.json", payload)
        before = self.model_metrics(directory, "before") if self.live_model else {}
        _, task, _ = self.admin.request(url + "/api/agent/events/ingest", payload, self.csrf, allowed=(202,))
        save(directory / "accepted-task.json", task)
        task = self.wait_task(task, 240 if self.live_model else 60, "investigation " + case["id"])
        save(directory / "task.json", task)
        workspace = self.admin.request(url + "/api/agent/sessions/" + task["sessionId"] + "/workspace")[1]
        save(directory / "workspace.json", workspace)
        if self.live_model:
            after = self.model_metrics(directory, "after")
            model_evidence = {"calls": {key: value - before.get(key, 0) for key, value in after.items()}}
            save(directory / "live-model.json", model_evidence)
            model_evidence.update(validate_live_analysis(task))
            save(directory / "live-model.json", model_evidence)
            require(model_evidence["calls"].get("解读:ok", 0) > 0, "No successful actual model explanation was measured")
        require(task["status"] == "COMPLETED", "Alert investigation did not complete")
        require(workspace["session"]["userId"] == "admin" and workspace["activeIncident"]["triggeredBy"] == "admin",
                "Alert source or handler was lost")
        findings = task["result"]["hypotheses"]
        require(not any(item["status"] == "REJECTED" for item in findings), "Current state incorrectly refuted a historical fault")
        if case["confirmedHypothesis"]:
            require(any(item["id"] == case["confirmedHypothesis"] and item["status"] == "CONFIRMED" for item in findings),
                    "Observed in-window 503 was not confirmed")
        evidence = task["result"]["evidence"]
        if getattr(self.args, "require_hypothesis_contract", False):
            save(directory / "hypothesis-contract.json", validate_hypothesis_contract(task))
        require(bool(evidence), "No investigation evidence")
        if getattr(self.args, "require_change_timeline", False):
            checks = validate_change_timeline(evidence, self.timeline_release, window)
            save(directory / "change-timeline-contract.json", checks)
        require(any(item["type"] == "INSTANCE" and "健康 2 个" in item["summary"] for item in evidence),
                "Fixture has two healthy registered versions, but instance evidence does not describe them")
        require(all(item["metadata"].get("requestedFromMillis") == str(window["fromMillis"])
                    and item["metadata"].get("requestedToMillis") == str(window["toMillis"]) for item in evidence),
                "Evidence window differs from replay window")
        require(any(item["type"] == "TRACE" and item["metadata"].get("temporalScope") == "REQUESTED_WINDOW"
                    and int(item["metadata"].get("sampleSize", 0)) > 0 for item in evidence), "No real trace samples reached the agent")
        if getattr(self.args, "require_evidence_contract", False):
            checks = validate_evidence_contract(evidence, case["expectedFailureStatus"], self.fixture["routeId"],
                                                "127.0.0.1:" + str(self.args.base_port + 4))
            native = self.manage("/metrics/routes?" + urllib.parse.urlencode({"routeId": self.fixture["routeId"],
                                  "fromMillis": window["fromMillis"], "toMillis": window["toMillis"]}))
            fact = native.get("routeWindow", {})
            require(fact.get("schemaVersion") == 1 and fact.get("samplingMode") == "ALL_FINAL_REQUESTS",
                    "Gateway did not expose its native final-response window")
            require(fact.get("qualityStatus") in ("AVAILABLE", "PARTIAL", "EMPTY", "OUT_OF_RANGE"),
                    "Native window quality is unknown")
            require(0 <= fact["final5xx"] <= fact["finalRequests"] <= window["requests"],
                    "Native historical window mixed requests outside the requested interval")
            if fact["qualityStatus"] == "OUT_OF_RANGE":
                require(fact["coverageFromMillis"] is None and fact["final5xxRate"] is None,
                        "Missing history was presented as a healthy rate")
            else:
                require(window["fromMillis"] <= fact["coverageFromMillis"] <= fact["coverageToMillis"] <= window["toMillis"],
                        "Native coverage differs from the incident interval")
            save(directory / "evidence-contract.json", {"checks": checks, "nativeWindow": native})
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
        if getattr(self.args, "require_change_timeline", False):
            self.publish_fixture_release(directory, case, repetition)
        self.fault(case["fault"]["status"], case["fault"]["delayMillis"])
        failed, result["fault"] = self.traffic(directory, "fault")
        expected = {row["key"]: row["responseVersion"] for row in baseline}
        require(all(row["status"] == (case["expectedFailureStatus"] if expected[row["key"]] == "v2" else 200)
                    for row in failed), "Fault outcomes do not match the baseline's sticky version assignment")
        require(result["fault"]["failures"] > 0, "Injected fault did not affect real traffic")
        # Close trace completion before querying the exact client-observed window.
        time.sleep(.1)
        result["alert"] = self.alert(directory, case, result["fault"])
        mitigation_started = time.time_ns() // 1_000_000
        result["mitigation"] = self.weight(0)
        require(result["mitigation"]["response"]["status"] == "APPLIED", "Fixture mitigation failed")
        mitigated, result["mitigated"] = self.traffic(directory, "mitigated")
        require(result["mitigated"]["failures"] == 0 and result["mitigated"]["responseVersions"] == {"v1": len(mitigated)},
                "Stopping v2 did not restore the sampled requests")
        if getattr(self.args, "require_change_timeline", False):
            self.verify_timeline_mitigation(directory, mitigation_started)
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
        if getattr(self.args, "require_change_timeline", False):
            self.setup_change_timeline()
        if self.live_model:
            self.verify_model()
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
        if getattr(self.args, "require_hypothesis_contract", False):
            self.report["hypothesisContractGatePassed"] = True
        if getattr(self.args, "require_change_timeline", False):
            self.report["changeTimelineGatePassed"] = True
        if getattr(self.args, "require_evidence_contract", False):
            self.report["evidenceContractGatePassed"] = True
        if self.live_model:
            self.live_conversation()
            counts = self.model_metrics(self.output, "final")
            self.report["modelCalls"] = counts
            # Planner success and explanation success are separate: neither can be hidden by fallback.
            self.report["liveModelGatePassed"] = counts.get("调查规划:ok", 0) > 0 and counts.get("解读:ok", 0) >= len(self.report["cases"])
            require(self.report["liveModelGatePassed"], "Live model planning/explanation gate failed")
        self.receipts()
        if self.live_model:
            self.model_failure_probe()
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
        if self.live_model:
            # Delete only the two copies created in this fresh owned fixture; never touch the source credentials.
            for filename in ("model.properties", "master.key"):
                try:
                    (self.component_dir("admin") / "config" / filename).unlink(missing_ok=True)
                except OSError as error:
                    errors.append("credential-copy: " + type(error).__name__)
        self.report["cleanupPassed"] = not errors and all(p.poll() is not None for p in self.processes.values())
        self.report["cleanupErrors"] = errors
        save(self.output / "result.json", self.report)
        lines = ["# Agent fault replay", "", f"Fault replay: {self.report['faultReplayPassed']}",
                 f"Cleanup: {self.report['cleanupPassed']}",
                 f"Receipt production gate: {self.report['receiptProductionGatePassed']}", "",
                 f"Mitigation actor: test-harness; model mode: {self.report['modelMode']}.", "",
                 "| Scenario | Run | Baseline errors | Fault errors | Mitigated errors | Passed |",
                 "| --- | --- | --- | --- | --- | --- |"]
        for row in self.report["cases"]:
            counts = [str(row.get(phase, {}).get("failures", "incomplete")) for phase in ("baseline", "fault", "mitigated")]
            lines.append(f"| {row['scenario']} | {row['repetition']} | {' | '.join(counts)} | {row['passed']} |")
        lines += ["", "Receipt checks: " + json.dumps(self.report.get("receiptProductionChecks", {})),
                  "Evidence contract gate: " + str(self.report.get("evidenceContractGatePassed", "not run")),
                  "", "Error: " + str(self.report["error"]), "",
                  "Raw evidence: cases/*/*-requests.jsonl, task.json, workspace.json, receipt-probe.json.",
                  "Metrics are rolling snapshots; phase error rates use actual client request outcomes.",
                  "This run does not certify capacity, general LLM quality, automatic remediation or production readiness."]
        if self.live_model:
            lines += ["", "Live model gate: " + str(self.report.get("liveModelGatePassed")),
                      "Model calls: " + json.dumps(self.report.get("modelCalls", {}), ensure_ascii=False),
                      "Live evidence: live-model-connection.json, cases/*/live-model.json, *-agent-metrics.prom.",
                      "Model explanations require human evidence review; repair/reset operations belong to the test harness."]
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
    parser.add_argument("--require-evidence-contract", action="store_true", help="Verify S2 captured identity, response origin, evidence quality and native historical windows")
    parser.add_argument("--require-change-timeline", action="store_true", help="Verify release-only ingress, idempotent release evidence, pre-incident association and actual observed route mitigation")
    parser.add_argument("--require-hypothesis-contract", action="store_true", help="Verify hypothesis evidence references, unknown gaps, persisted progress and bounded attempts")
    parser.add_argument("--model-config", type=Path, help="Opt in to actual paid model calls using this existing encrypted Rover model config")
    parser.add_argument("--model-master-key-file", type=Path, help="Existing master-key file; defaults to master.key next to --model-config")
    args = parser.parse_args()
    parser.error("Java 17+ must be on PATH or supplied with --java") if not args.java else None
    require(1024 <= args.base_port <= 65530 and 1 <= args.startup_timeout <= 300, "Invalid port/timeout")
    require(128 <= args.heap_mb <= 512, "Invalid fixture heap cap")
    require(not args.model_master_key_file or args.model_config, "--model-master-key-file requires --model-config")
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
