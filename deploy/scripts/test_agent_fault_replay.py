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

SPEC = importlib.util.spec_from_file_location("replay", Path(__file__).with_name("agent-fault-replay.py"))
replay = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(replay)


class ReplayTest(unittest.TestCase):
    def test_empty_traffic_cannot_be_reported_as_recovered(self):
        with self.assertRaisesRegex(RuntimeError, "No traffic samples"):
            replay.summarize([])

    def test_transport_failures_count_in_the_error_rate_denominator(self):
        rows = [dict(key="a", status=200, responseVersion="v1", durationMillis=1, startMillis=10, endMillis=11),
                dict(key="b", status=0, responseVersion="", durationMillis=10, startMillis=11, endMillis=21)]
        summary = replay.summarize(rows)
        self.assertEqual((2, 1, .5), (summary["requests"], summary["failures"], summary["errorRate"]))
        self.assertEqual({"v1": 1, "unknown": 1}, summary["responseVersions"])

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


if __name__ == "__main__":
    unittest.main()
