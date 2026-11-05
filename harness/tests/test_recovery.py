import datetime as dt
import hashlib
import json
import pathlib
import sys
import tempfile
import unittest
import uuid

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import recovery

UTC = dt.timezone.utc
NOW = dt.datetime(2026, 10, 28, 12, tzinfo=UTC)


def event(kind, time, session, task=None):
    return {'schemaVersion': 1, 'eventId': str(uuid.uuid4()), 'sessionId': session,
            'occurredAt': time.isoformat(), 'source': 'android', 'category': 'work',
            'type': kind, 'evidence': 'timer', 'metrics': {}, 'taskRef': task}


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.output = pathlib.Path(self.tmp.name)
        self.config = {'outputDirectory': str(self.output), 'vaultTelemetryRoots': ['/explicit/android']}

    def cache(self, rows):
        (self.output/'vault-android-0.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in rows))

    def test_opt_in_stale_and_stop_preserve_context_and_history(self):
        self.assertFalse(recovery.evaluate(self.config, {}, NOW)['enabled'])
        session = str(uuid.uuid4())
        self.cache([event('started', NOW-dt.timedelta(hours=2), session, 'task-1'),
                    event('paused', NOW-dt.timedelta(hours=1), session)])
        recovery.configure(self.config, True, 0, 4, NOW)
        got = recovery.evaluate(self.config, {'vaultSync0': {'state': 'observed'}}, NOW)
        self.assertEqual(got['state'], 'available')
        self.assertEqual(got['context']['taskRef'], 'task-1')
        self.assertEqual(got['context']['sessionState'], 'paused')
        stale = recovery.evaluate(self.config, {'vaultSync0': {'state': 'missing'}}, NOW+dt.timedelta(hours=5))
        self.assertEqual(stale['state'], 'source_unavailable')
        self.assertIn('deletion is not established', stale['explanation'])
        self.assertEqual(stale['context'], got['context'])
        stopped = recovery.configure(self.config, False, now=NOW+dt.timedelta(hours=6))
        self.assertEqual(stopped['state'], 'disabled')
        self.assertEqual(stopped['context'], got['context'])
        self.assertEqual(recovery.evaluate(self.config, {'vaultSync0': {'state': 'observed'}}, NOW+dt.timedelta(hours=7)), stopped)
        self.assertEqual([json.loads(line)['event'] for line in (self.output/'recovery-history.jsonl').read_text().splitlines()],
                         ['enabled', 'state_changed', 'state_changed', 'disabled'])

    def test_future_and_invalid_events_are_never_context(self):
        self.cache([event('started', NOW+dt.timedelta(hours=1), str(uuid.uuid4()), 'future'), {'private': 'text'}])
        recovery.configure(self.config, True, 0, 4, NOW)
        got = recovery.evaluate(self.config, {'vaultSync0': {'state': 'observed'}}, NOW)
        self.assertEqual(got['state'], 'source_unavailable')
        self.assertIsNone(got['context'])
        self.assertIsNone(got['lastObservedAtUTC'])

    def test_scheduler_evaluates_verified_batch_and_stops(self):
        import scheduler
        root = self.output/'events/android'
        producer = root/str(uuid.uuid4())
        producer.mkdir(parents=True)
        row = event('started', NOW-dt.timedelta(minutes=5), str(uuid.uuid4()), 'task-1')
        content = (json.dumps(row)+'\n').encode()
        (producer/(hashlib.sha256(content).hexdigest()+'.jsonl')).write_bytes(content)
        generated = scheduler.prepare(str(self.output/'private'), [], vault_roots=[str(root)])
        path = pathlib.Path(generated['config'])
        config = json.loads(path.read_text())
        config['enabledByUser'] = True
        path.write_text(json.dumps(config))
        recovery.configure(config, True, 0, 4, NOW)
        scheduler.run(path, NOW)
        report_path = pathlib.Path(config['outputDirectory'])/'recovery.json'
        report = json.loads(report_path.read_text())
        self.assertEqual(report['state'], 'available')
        self.assertEqual(report['context']['taskRef'], 'task-1')
        recovery.configure(config, False, now=NOW)
        root.rename(self.output/'offline')
        scheduler.run(path, NOW+dt.timedelta(hours=8))
        self.assertEqual(json.loads(report_path.read_text())['state'], 'disabled')

    def test_changed_source_never_relabels_old_context(self):
        self.cache([event('started', NOW, str(uuid.uuid4()), 'task-1')])
        recovery.configure(self.config, True, 0, 4, NOW)
        recovery.evaluate(self.config, {'vaultSync0': {'state': 'observed'}}, NOW)
        self.config['vaultTelemetryRoots'][0] = '/different/android'
        report = recovery.evaluate(self.config, {'vaultSync0': {'state': 'observed'}}, NOW)
        self.assertEqual(report['state'], 'unknown')
        selected = recovery.configure(self.config, True, 0, 4, NOW)
        self.assertIsNone(selected['context'])
        self.assertIsNone(selected['lastObservedAtUTC'])

    def test_explicit_source_and_interval_required(self):
        for index, hours in [(1, 4), (0, None), (0, 0), (-1, 2)]:
            with self.assertRaises(ValueError): recovery.configure(self.config, True, index, hours, NOW)
        self.assertFalse((self.output/'recovery-control.json').exists())


if __name__ == '__main__': unittest.main()
