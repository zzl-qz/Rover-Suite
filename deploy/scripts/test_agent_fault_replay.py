"""Checks for replay evidence integrity and safe failure before launching processes."""
import argparse
import copy
import importlib.util
import http.server
import json
from pathlib import Path
import socket
import tempfile
import threading
import unittest
import unittest.mock

SPEC = importlib.util.spec_from_file_location("replay", Path(__file__).with_name("agent-fault-replay.py"))
replay = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(replay)


class ReplayTest(unittest.TestCase):
    def test_mitigation_timeline_requires_same_route_change_and_zero_weight(self):
        event = {"kind": "ROUTE_CHANGED", "subject": {"routeId": "replay-orders"},
                 "observedAtMillis": 1200, "facts": {"after": {"targets": [
                     {"group": "v1", "weight": 100}, {"group": "v2", "weight": 0}]}}}
        self.assertTrue(replay.Replay.route_mitigation_observed([event], "replay-orders", 1100))
        event["facts"]["after"]["targets"][1]["weight"] = 50
        self.assertFalse(replay.Replay.route_mitigation_observed([event], "replay-orders", 1100))
        event["facts"]["after"]["targets"][1]["weight"] = 0
        self.assertFalse(replay.Replay.route_mitigation_observed([event], "another-route", 1100))
        self.assertFalse(replay.Replay.route_mitigation_observed([event], "replay-orders", 1201))
        event["kind"] = "ROUTE_APPEARED"
        self.assertFalse(replay.Replay.route_mitigation_observed([event], "replay-orders", 1100))

    def test_conversation_does_not_require_traces_but_cannot_mislabel_gateway_failures(self):
        task = {"result": {"summary": "根因尚未确认。最终请求 35，最终 5xx 35；后端原始 5xx=0，网关生成 5xx=35 [evidenceId=e] 尚未执行修复或验证业务恢复",
                "evidence": [{"evidenceId": "e", "envelope": {"sourceId": "gateway.final-responses", "facts": {
                    "finalRequests": 35, "final5xx": 35, "upstream5xx": 0, "gateway5xx": 35}}}],
                "hypotheses": [{"id": "H5", "status": "UNKNOWN"}, {"id": "H6", "status": "CONFIRMED",
                    "statement": "访问上游实例时出现 5xx", "detail": "不能据此确认响应来源或业务根因"}]}}
        self.assertIsNone(replay.validate_conversation_report(task)["sampleChecks"])
        wrong = copy.deepcopy(task)
        wrong["result"]["hypotheses"][1]["statement"] = "上游实例返回了 5xx"
        with self.assertRaisesRegex(RuntimeError, "backend response codes"):
            replay.validate_conversation_report(wrong)
        wrong = copy.deepcopy(task)
        wrong["result"]["summary"] = wrong["result"]["summary"].replace("后端原始 5xx=0", "后端原始 5xx=35")
        with self.assertRaises(RuntimeError):
            replay.validate_conversation_report(wrong)

    def test_grounded_report_checks_published_numbers_and_comparison_group_against_rows(self):
        populations, rows, lines = [], [], []
        for group, status in (("v1", 200), ("v2", 503)):
            identity = dict(routeId="r", resourceUid="uid", generation=1, serviceName="svc", group=group, hostPort=group + ":80")
            populations.append(identity)
            rows.append(dict(identity, statusCode=status, responseOrigin="UPSTREAM", failurePhase="NONE"))
            failures = int(status == 503)
            lines.append(f"- 保留的追踪样本：路由=r，服务=svc，版本={group}，实例={group}:80，请求=1，5xx={failures}，"
                         f"sample5xxRate={failures}，响应来源={{UPSTREAM=1}}，故障阶段样本={{NONE=1}} [evidenceId=e]")
        report = ("根因尚未确认。\n" + "\n".join(lines)
                  + "\n不代表完整流量或版本整体健康；尚未执行修复或验证业务恢复")
        task = {"result": {"aiAnalysis": report, "evidence": [{"evidenceId": "e", "envelope": {
            "facts": {"rows": rows, "samplePopulations": populations}}}]}}
        self.assertEqual(2, replay.validate_grounded_report(task)["capturedPopulationsChecked"])
        for broken in (report.replace("请求=1", "请求=2", 1), report.replace("sample5xxRate=1", "sample5xxRate=0"),
                       report.replace(lines[0], ""), report.replace("[evidenceId=e]", "[evidenceId=invented]"),
                       report.replace("{UPSTREAM=1}", "{GATEWAY=1}"), report.replace("{NONE=1}", "{CONNECT=1}")):
            with self.subTest(broken=broken):
                wrong = copy.deepcopy(task)
                wrong["result"]["aiAnalysis"] = broken
                with self.assertRaises(RuntimeError):
                    replay.validate_grounded_report(wrong)

    def test_change_timeline_requires_pre_incident_release_and_honest_query_scope(self):
        event = {"eventId": "e", "sourceId": "release:ci", "sourceEventId": "ci-1", "occurredAtMillis": 900,
                 "scope": {"environmentId": "test"}, "controlTargetId": "gateway", "subject": {"group": "v2"},
                 "facts": {"releaseId": "deploy-1", "artifact": "build-1"}}
        envelope = {"sourceId": "rover.change-timeline", "qualityStatus": "PARTIAL", "coverageRange": None,
                    "context": {"scope": event["scope"], "controlTargetId": "gateway"}, "facts": {"events": [event],
                    "queryRange": {"fromMillis": 100, "toMillis": 2000}, "investigationRange": {"fromMillis": 1000, "toMillis": 2000},
                    "causalityConfirmed": False}}
        self.assertTrue(replay.validate_change_timeline([{"envelope": envelope}], event, {"fromMillis": 1000, "toMillis": 2000})["preIncidentReleaseAssociated"])
        for field, value in (("qualityStatus", "AVAILABLE"), ("coverageRange", {"fromMillis": 100, "toMillis": 2000})):
            wrong = copy.deepcopy(envelope)
            wrong[field] = value
            with self.assertRaises(RuntimeError):
                replay.validate_change_timeline([{"envelope": wrong}], event, {"fromMillis": 1000, "toMillis": 2000})
        wrong = copy.deepcopy(envelope)
        wrong["facts"]["events"][0]["facts"]["artifact"] = "invented-build"
        with self.assertRaises(RuntimeError):
            replay.validate_change_timeline([{"envelope": wrong}], event, {"fromMillis": 1000, "toMillis": 2000})

    def test_response_origin_gate_rejects_status_only_guesses_and_wrong_version(self):
        scope = {"organizationId": "org", "teamId": "team", "environmentId": "test"}
        row = {"statusCode": 503, "routeId": "r", "group": "v2", "hostPort": "h:80", "resourceUid": "uid",
               "generation": 1, "responseOrigin": "UPSTREAM", "failurePhase": "NONE", "attempts": [{}]}
        evidence = [{"taskId": "t", "type": "TRACE", "envelope": {"schemaVersion": 1, "qualityStatus": "PARTIAL",
                     "context": {"scope": scope, "controlTargetId": "gw", "taskId": "t"}, "facts": {"rows": [row],
                         "samplePopulations": [{"group": "v2", "hostPort": "h:80", "sampleRequests": 1, "sample5xx": 1, "sample5xxRate": 1}]},
                     "resourceRefs": [{"scope": scope, "resourceUid": "uid", "generation": 1}]}}]
        self.assertEqual(1, replay.validate_evidence_contract(evidence, 503, "r", "h:80")["capturedFailureSamples"])
        for field, value in (("responseOrigin", "GATEWAY"), ("group", "v1"), ("resourceUid", "invented")):
            wrong = copy.deepcopy(evidence)
            wrong[0]["envelope"]["facts"]["rows"][0][field] = value
            with self.assertRaises(RuntimeError):
                replay.validate_evidence_contract(wrong, 503, "r", "h:80")
        wrong = copy.deepcopy(evidence)
        wrong[0]["envelope"]["facts"]["samplePopulations"][0]["sampleRequests"] = 62
        with self.assertRaises(RuntimeError):
            replay.validate_evidence_contract(wrong, 503, "r", "h:80")

    def test_rule_fallback_cannot_pass_a_live_model_test(self):
        task = {"status": "COMPLETED", "result": {"aiAnalysis": None}, "steps": [
            {"type": "AI_EXPLANATION", "status": "FAILED"}]}
        with self.assertRaisesRegex(RuntimeError, "rule fallback"):
            replay.validate_live_analysis(task)
        task["result"]["aiAnalysis"] = "partial streamed text before provider failure"
        with self.assertRaises(RuntimeError):
            replay.validate_live_analysis(task)

    def test_live_model_requires_completed_explanation_and_nonempty_answer(self):
        task = {"status": "RUNNING", "result": {"aiAnalysis": "evidence-based answer"}, "steps": [
            {"type": "AI_EXPLANATION", "status": "COMPLETED"}]}
        with self.assertRaises(RuntimeError):
            replay.validate_live_analysis(task)
        task["status"] = "COMPLETED"
        self.assertEqual("evidence-based answer", replay.validate_live_analysis(task)["analysis"])

    def test_live_credential_cleanup_removes_only_fixture_copies(self):
        temporary = replay.ROOT / ".tmp"
        temporary.mkdir(exist_ok=True)
        fixture = json.loads(replay.SCENARIOS.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory(dir=temporary) as work:
            config = Path(work) / "original.properties"
            key = Path(work) / "original.key"
            config.write_text("encrypted-test-config", encoding="utf-8")
            key.write_text("test-key", encoding="utf-8")
            args = argparse.Namespace(output=Path(work) / "result", base_port=19080, model_config=config)
            run = replay.Replay(args, fixture)
            private = run.component_dir("admin") / "config"
            private.mkdir(parents=True)
            (private / "model.properties").write_bytes(config.read_bytes())
            (private / "master.key").write_bytes(key.read_bytes())
            run.close()
            self.assertEqual("encrypted-test-config", config.read_text(encoding="utf-8"))
            self.assertEqual("test-key", key.read_text(encoding="utf-8"))
            self.assertEqual([], list(private.iterdir()))

    def test_empty_traffic_cannot_be_reported_as_recovered(self):
        with self.assertRaisesRegex(RuntimeError, "No traffic samples"):
            replay.summarize([])

    def test_transport_failures_count_in_the_error_rate_denominator(self):
        rows = [dict(key="a", status=200, responseVersion="v1", durationMillis=1, startMillis=10, endMillis=11),
                dict(key="b", status=0, responseVersion="", durationMillis=10, startMillis=11, endMillis=21)]
        summary = replay.summarize(rows)
        self.assertEqual((2, 1, .5), (summary["requests"], summary["failures"], summary["errorRate"]))
        self.assertEqual({"v1": 1, "unknown": 1}, summary["responseVersions"])

    def test_capacity_summary_separates_admission_from_task_completion(self):
        rows = [
            {"status": 202, "latencyMillis": 10, "taskStatus": "COMPLETED", "error": None},
            {"status": 202, "latencyMillis": 20, "taskStatus": "FAILED", "error": "db unavailable"},
            {"status": 0, "latencyMillis": 30, "taskStatus": "TIMEOUT", "error": "deadline"},
        ]
        result = replay.summarize_burst(rows, 2.0, 123.4)
        self.assertEqual((3, 2, 1, 2), (result["submitted"], result["accepted"],
                                         result["completed"], result["failedOrUnknown"]))
        self.assertEqual({"p50": 20, "p95": 30, "p99": 30, "max": 30},
                         result["admissionLatencyMillis"])
        self.assertFalse(result["allAcceptedAndCompleted"])
        self.assertEqual(123.4, result["peakAdminWorkingSetMiB"])

    def test_prometheus_monitoring_requires_loopback_scrape_and_agent_metric(self):
        class Probe:
            def __init__(self, status, body): self.status, self.body = status, body
            def request(self, url, **kwargs):
                if url == "http://127.0.0.1:19185/actuator/prometheus":
                    return 404, {}, {}
                return self.status, self.body, getattr(self, "response_headers", {})
        run = replay.Replay.__new__(replay.Replay)
        run.args = argparse.Namespace(base_port=19180, enable_prometheus=True)
        run.management_url = "http://127.0.0.1:19186"
        run.urls = {"admin": "http://127.0.0.1:19185"}
        run.http = Probe(200, "# HELP rover_agent_task_submitted_total Tasks")
        run.http.response_headers = {"Content-Type": "text/plain; version=0.0.4"}
        run.report = {}
        with tempfile.TemporaryDirectory() as temporary:
            run.http = Probe(200, "# HELP rover_agent_task_submitted_total Tasks")
            run.http.response_headers = {"Content-Type": "text/plain; version=0.0.4"}
            run.prometheus_probe(Path(temporary))
            self.assertTrue(run.report["prometheusGatePassed"])
            self.assertEqual(19186, run.report["prometheusProbe"]["managementPort"])
        run.http = Probe(200, "unrelated_metric 1")
        run.http.response_headers = {"Content-Type": "text/plain; version=0.0.4"}
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(RuntimeError, "missing Agent task metrics"):
                run.prometheus_probe(Path(temporary))

    def test_prometheus_production_template_uses_a_separate_loopback_port(self):
        template = (replay.ROOT / "deploy/production/admin-application.example.yml").read_text(encoding="utf-8")
        self.assertIn("address: 127.0.0.1", template)
        self.assertIn("port: ${ROVER_ADMIN_MANAGEMENT_PORT:9091}", template)

    def test_soak_summary_tracks_a_bounded_database_growth_budget(self):
        rows = [{"status": 202, "admissionLatencyMillis": 25, "taskStatus": "COMPLETED"}]
        result = replay.summarize_soak(rows, 10, 11, 100, 1024, 2048, 0)
        self.assertTrue(result["databaseGrowthWithinLimit"])
        self.assertTrue(result["allAlertsAcceptedAndCompleted"])
        result = replay.summarize_soak(rows, 10, 11, 100, 4096, 2048, 0)
        self.assertFalse(result["databaseGrowthWithinLimit"])

    def test_repeatability_compares_per_user_outcomes_not_completion_order(self):
        rows = [dict(key="a", status=200, responseVersion="v1"), dict(key="b", status=503, responseVersion="v2")]
        self.assertEqual(replay.signature(rows), replay.signature(list(reversed(rows))))
        changed = [dict(key="a", status=503, responseVersion="v2"), dict(key="b", status=200, responseVersion="v1")]
        self.assertNotEqual(replay.signature(rows), replay.signature(changed))

    def test_fixture_cannot_redirect_replay_to_another_route_or_change_fault_truth(self):
        fixture = json.loads(replay.SCENARIOS.read_text(encoding="utf-8"))
        replay.validate_fixture(fixture)
        changed = copy.deepcopy(fixture)
        changed["path"] = "/api/production"
        with self.assertRaises(RuntimeError):
            replay.validate_fixture(changed)
        changed = copy.deepcopy(fixture)
        changed["scenarios"][0]["fault"]["delayMillis"] = 100000
        with self.assertRaises(RuntimeError):
            replay.validate_fixture(changed)

    def test_non_loopback_urls_are_rejected_before_any_request(self):
        with self.assertRaisesRegex(RuntimeError, "loopback"):
            replay.Http().request("https://example.com/api")

    def test_real_http_success_and_error_responses_are_classified(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(403 if self.path == "/deny" else 200)
                self.end_headers()
                self.wfile.write(b'{}')

            def log_message(self, *args):
                pass
        with http.server.HTTPServer(("127.0.0.1", 0), Handler) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                base = f"http://127.0.0.1:{server.server_port}"
                self.assertEqual((200, {}), replay.Http().request(base)[:2])
                self.assertEqual(403, replay.Http().request(base + "/deny", allowed=(403,))[0])
                with self.assertRaisesRegex(RuntimeError, "HTTP 403"):
                    replay.Http().request(base + "/deny")
            finally:
                server.shutdown()
                worker.join(timeout=2)

    def test_occupied_port_fails_without_starting_or_terminating_existing_services(self):
        temporary = replay.ROOT / ".tmp"
        temporary.mkdir(exist_ok=True)
        fixture = json.loads(replay.SCENARIOS.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory(dir=temporary) as work, socket.socket() as existing:
            existing.bind(("127.0.0.1", 0))
            existing.listen()
            args = argparse.Namespace(output=Path(work) / "result", base_port=existing.getsockname()[1],
                                      java="java", startup_timeout=1)
            run = replay.Replay(args, fixture)
            try:
                with self.assertRaises(OSError):
                    run.configure()
                self.assertFalse(run.processes)
                self.assertGreater(existing.fileno(), -1)
            finally:
                run.close()
            receipt = json.loads((args.output / "result.json").read_text(encoding="utf-8"))
            self.assertTrue(receipt["cleanupPassed"])
            self.assertFalse(receipt["faultReplayPassed"])


class HypothesisContractTest(unittest.TestCase):
    def test_gate_rejects_forged_references_missing_gaps_and_unbounded_retries(self):
        task = {"result": {"hypotheses": [{"id": "H7", "status": "UNKNOWN", "missingEvidence": ["应用日志"],
                  "supportingEvidenceIds": ["e1"], "counterEvidenceIds": []}], "evidence": [{"evidenceId": "e1"}]},
                "plan": {"progress": {"rounds": 2, "toolCalls": 1, "stopReason": "无法确认",
                  "attempts": [{"capability": "TRACE_QUERY"}]}}}
        replay.validate_hypothesis_contract(task)
        for mutation in ("forged", "gap", "retry", "model"):
            changed = copy.deepcopy(task)
            if mutation == "forged":
                changed["result"]["hypotheses"][0]["supportingEvidenceIds"] = ["fake"]
            elif mutation == "gap":
                changed["result"]["hypotheses"][0]["missingEvidence"] = []
            elif mutation == "retry":
                changed["plan"]["progress"]["attempts"] *= 3
            else:
                changed["result"]["hypotheses"][0].update(id="M1", status="CONFIRMED")
            with self.subTest(mutation=mutation), self.assertRaises(RuntimeError):
                replay.validate_hypothesis_contract(changed)


if __name__ == "__main__":
    unittest.main()
