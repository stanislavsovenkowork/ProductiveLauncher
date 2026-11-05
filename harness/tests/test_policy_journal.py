import json
import tempfile
import unittest
import uuid
from pathlib import Path

from harness.policy_journal import PolicyJournal


def uid():
    return str(uuid.uuid4())


class JournalTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "journal.jsonl"
        self.j = PolicyJournal(self.path, opted_in=True, target_key=b"k" * 32)
        self.policy = dict(policyVersion=2, windowId=uid(), localDate="2026-10-28",
                           startsAt="2026-10-28T12:00:00Z", endsAt="2026-10-28T13:00:00Z",
                           targetRefs=["https://private.example/path", "secret application"],
                           name="private name", reliefBudgetSeconds=300,
                           reliefMaxGrantSeconds=300, exitCoolingOffSeconds=15)
        self.args = dict(operation_id=uid(), version_id=uid(), observed_at="2026-10-28T12:00:00Z",
                         source="mac", policy=self.policy)

    def tearDown(self):
        self.tmp.cleanup()

    def test_opt_in_and_private_snapshot(self):
        with self.assertRaises(ValueError):
            PolicyJournal(self.path, opted_in=False, target_key=b"k" * 32)
        self.assertFalse(self.path.exists())
        self.j.propose(**self.args)
        data = self.path.read_text()
        self.assertNotIn("private", data)
        self.assertNotIn("secret", data)
        self.assertEqual(json.loads(data)["observedStatus"], "unknown")

    def test_retry_and_commit_are_distinct(self):
        first = self.j.propose(**self.args)
        self.assertEqual(first, self.j.propose(**self.args))
        with self.assertRaises(ValueError):
            self.j.propose(**{**self.args, "source": "android"})
        commit = dict(operation_id=uid(), version_id=self.args["version_id"],
                      observed_at="2026-10-28T12:00:01Z", source="mac")
        self.j.confirm(**commit)
        self.j.confirm(**commit)
        records = [json.loads(x) for x in self.path.read_text().splitlines()]
        self.assertEqual([r["kind"] for r in records], ["proposal", "commit"])
        self.assertEqual(records[1]["observedStatus"], "unknown")
        with self.assertRaises(ValueError):
            self.j.confirm(**{**commit, "operation_id": uid()})

    def test_missing_proposal_and_partial_tail_fail_closed(self):
        with self.assertRaises(ValueError):
            self.j.confirm(operation_id=uid(), version_id=uid(), observed_at="2026-10-28T12:00:00Z", source="mac")
        self.j.propose(**self.args)
        with self.path.open("ab") as f:
            f.write(b'{"incomplete":')
        before = self.path.read_bytes()
        with self.assertRaises(ValueError):
            self.j.confirm(operation_id=uid(), version_id=self.args["version_id"], observed_at="2026-10-28T12:00:01Z", source="mac")
        self.assertEqual(before, self.path.read_bytes())


if __name__ == "__main__":
    unittest.main()
