"""Synthetic boundary tests; never reads live apps, databases or personal notes."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "shared"))
from validate_events import validate


def event(start, end, seconds, source="android", kind="completed"):
    return {"schemaVersion": 1, "eventId": str(uuid.uuid4()),
            "sessionId": "c0ff83df-491b-45e2-a24f-af0cb53b4277", "occurredAt": end,
            "source": source, "category": "study", "type": kind, "evidence": "timer",
            "startedAt": start, "endedAt": end, "metrics": {"focusSeconds": seconds},
            "coverage": "partial"}


class ContractIntegrationTests(unittest.TestCase):
    def cycle(self, events):
        with tempfile.TemporaryDirectory() as temp:
            path, output = Path(temp)/"synthetic.jsonl", Path(temp)/"review.json"
            for row in events:
                validate(row)
            path.write_text("".join(json.dumps(row)+"\n" for row in events))
            subprocess.run([sys.executable, str(ROOT/"harness/metrics.py"), "cycle",
                            "--events", str(path), "--day", "2026-11-02", "--as-of", "2026-11-02T20:00:00Z",
                            "--output", str(output)], check=True, capture_output=True, text=True)
            return json.loads(output.read_text())

    def test_paused_session_on_two_devices_has_no_double_time(self):
        first = event("2026-11-02T09:00:00Z", "2026-11-02T09:10:00Z", 600, kind="paused")
        second = event("2026-11-02T09:15:00Z", "2026-11-02T09:25:00Z", 600)
        mirror = [dict(row, source="mac", eventId=str(uuid.uuid4())) for row in (first, second)]
        report = self.cycle([first, second, *mirror])
        self.assertEqual(report["categories"]["study"]["focusSecondsByEvidence"]["timer"], 1200)
        self.assertIsNone(report["categories"]["work"]["focusHoursByEvidence"]["timer"])
        self.assertFalse(report["recovery"]["automaticReturnAfterFullDeletion"])

    def test_mac_health_never_becomes_measured_focus(self):
        row = event("2026-11-02T09:00:00Z", "2026-11-02T09:25:00Z", None, source="mac", kind="health")
        row.pop("startedAt"); row.pop("endedAt")
        row["protected"] = False
        report = self.cycle([row])
        self.assertIsNone(report["categories"]["study"]["focusHoursByEvidence"]["timer"])

    def test_actual_kotlin_exporter_fixture_through_daily_review(self):
        rows = [json.loads(line) for line in (ROOT/"integration/fixtures/android-session.jsonl").read_text().splitlines()]
        for row in rows:
            validate(row)
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)/"review.json"
            subprocess.run([sys.executable, str(ROOT/"harness/metrics.py"), "cycle",
                            "--events", str(ROOT/"integration/fixtures/android-session.jsonl"),
                            "--day", "2025-10-09", "--as-of", "2025-10-09T20:00:00Z", "--output", str(output)], check=True, capture_output=True)
            report = json.loads(output.read_text())
        self.assertEqual(report["categories"]["study"]["focusSecondsByEvidence"]["timer"], 900)
        self.assertEqual(report["categories"]["study"]["sessionsObserved"], 1)
        self.assertEqual(report["health"]["events0"]["invalid"], 0)
        self.assertIsNone(report["categories"]["work"]["focusSecondsByEvidence"]["timer"])

    def test_actual_swift_exporter_fixture_through_daily_review(self):
        fixture = ROOT/"integration/fixtures/mac-session.jsonl"
        rows = [json.loads(line) for line in fixture.read_text().splitlines()]
        for row in rows:
            validate(row)
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)/"review.json"
            subprocess.run([sys.executable, str(ROOT/"harness/metrics.py"), "cycle",
                            "--events", str(fixture), "--day", "2026-10-28", "--as-of", "2026-10-28T20:00:00Z",
                            "--output", str(output)], check=True, capture_output=True)
            report = json.loads(output.read_text())
        self.assertEqual(report["categories"]["work"]["focusSecondsByEvidence"]["timer"], 900)
        self.assertEqual(report["categories"]["work"]["sessionsObserved"], 1)
        self.assertEqual(report["health"]["events0"]["invalid"], 0)
        self.assertIsNone(report["categories"]["study"]["focusSecondsByEvidence"]["timer"])

    def test_contract_rejects_negative_and_unknown_metrics(self):
        row = event("2026-11-02T09:00:00Z", "2026-11-02T09:10:00Z", -1)
        with self.assertRaises(ValueError): validate(row)
        row["metrics"] = {"productivityScore": 100}
        with self.assertRaises(ValueError): validate(row)

    def test_contract_accepts_utc_offset_and_null(self):
        row = event("2026-11-02T09:00:00+00:00", "2026-11-02T09:10:00+00:00", None)
        self.assertEqual(validate(row), row)


if __name__ == "__main__":
    unittest.main()
