import importlib.util
import pathlib
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('metrics', pathlib.Path(__file__).parents[1] / 'metrics.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def event(category='work', source='mac', evidence='timer', start='2026-11-02T09:00:00Z', end='2026-11-02T10:00:00Z'):
    return dict(schemaVersion=1, eventId=str(m.uuid.uuid4()), sessionId='bb272158-6240-49bb-9a5a-559301076c99', category=category,
                source=source, occurredAt=end, startedAt=start, endedAt=end,
                metrics={'focusSeconds':3600}, evidence=evidence, type='completed')


class MetricsTests(unittest.TestCase):
    def report(self, rows):
        return m.review(rows, '2026-11-02', {}, timezone='UTC', now=m.instant('2026-11-02T23:59:59Z'))

    def test_missing_is_null(self):
        report = self.report([])
        self.assertIsNone(report['categories']['work']['focusHoursByEvidence']['timer'])
        self.assertEqual(report['recovery']['state'], 'unknown')
        self.assertFalse(report['recovery']['automaticReturnAfterFullDeletion'])

    def test_devices_and_repeated_events_do_not_double_count(self):
        one = event()
        rows = [one, one.copy(), event(source='android')]
        self.assertEqual(self.report(rows)['categories']['work']['focusHoursByEvidence']['timer'], 1)

    def test_work_study_overlap_is_ambiguous(self):
        report = self.report([event(), event(category='study')])
        self.assertEqual(report['ambiguousFocusSeconds'],3600)
        self.assertIsNone(report['categories']['study']['focusHoursByEvidence']['timer'])

    def test_evidence_not_added_twice(self):
        report = self.report([event(), event(evidence='self_report')])
        self.assertEqual(report['categories']['work']['focusHoursByEvidence']['self_report'], 1)
        self.assertIsNone(report['categories']['work']['focusHoursByEvidence']['timer'])

    def test_day_boundary_clipped(self):
        report = self.report([event(start='2026-11-01T23:30:00Z', end='2026-11-02T00:30:00Z')])
        self.assertEqual(report['categories']['work']['focusHoursByEvidence']['timer'], .5)

    def test_movebreak_does_not_count_planned_or_active_minutes(self):
        accepted, ignored = m.movebreak([{'type':'block_completed','at':'2026-11-02T10:00:00Z','minutes':60},
                                        {'type':'block_completed','at':'2026-11-02T10:00:00Z','actualSeconds':900},
                                        {'type':'task_started','at':'2026-11-02T10:00:00Z'}], 'study')
        self.assertEqual(len(accepted), 1)
        self.assertEqual(ignored, 2)
        self.assertIsNone(self.report(accepted)['categories']['study']['focusHoursByEvidence']['timer'])
        self.assertEqual(self.report(accepted)['categories']['study']['estimatedFocusSecondsByEvidence']['timer'],900)

    def test_missing_and_malformed_visible(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory)/'events.jsonl'
            self.assertEqual(m.read_lines(path)[1]['state'],'missing')
            path.write_text('{broken\n[]\n{}\n')
            rows, health = m.read_lines(path)
            self.assertEqual(health['invalid'],2)
            self.assertEqual(len(rows),1)

    def test_explicit_disable_is_not_inferred_from_silence(self):
        row = event()
        row['type'] = 'disabled'
        self.assertEqual(self.report([row])['recovery']['state'], 'disabled')

    def test_null_category_excluded(self):
        row = event(category=None)
        m.validate(row)
        self.assertEqual(self.report([row])['unknownCategoryEvents'], 1)
        self.assertEqual(self.report([row])['ambiguousFocusSeconds'], 3600)

    def test_minsk_and_dst_boundaries(self):
        row = event(start='2026-11-01T21:30:00Z', end='2026-11-01T22:30:00Z')
        report = m.review([row], '2026-11-02', {}, now=m.instant('2026-11-02T23:59:59Z'))
        self.assertEqual(report['categories']['work']['focusHoursByEvidence']['timer'], 1)
        a, b = m.day_window('2026-03-29', 'Europe/Berlin')
        self.assertEqual((b-a).total_seconds(), 23*3600)
        a, b = m.day_window('2026-10-25', 'Europe/Berlin')
        self.assertEqual((b-a).total_seconds(), 25*3600)

    def test_utc_offset_accepted(self):
        self.assertEqual(m.instant('2026-11-02T10:00:00+00:00'), m.instant('2026-11-02T10:00:00Z'))

    def test_conflicting_event_ids_quarantined(self):
        one = event()
        other = dict(one, category='study')
        report = self.report([one,other])
        self.assertEqual(report['rejectedConflictingRecords'], 2)
        self.assertIsNone(report['categories']['work']['focusHoursByEvidence']['timer'])

    def test_explicit_zero_not_null(self):
        row = event(start='2026-11-02T10:00:00Z')
        row['metrics']['focusSeconds'] = 0
        m.validate(row)
        self.assertEqual(self.report([row])['categories']['work']['focusHoursByEvidence']['timer'], 0)

    def test_resume_supersedes_stop_heartbeat_is_only_observation(self):
        stopped = event()
        stopped['type'] = 'stopped'
        resumed = event(end='2026-11-02T10:01:00Z')
        resumed['type'] = 'resumed'
        heartbeat = event(end='2026-11-02T10:02:00Z')
        heartbeat['type'] = 'heartbeat'
        report = m.review([stopped,resumed,heartbeat], '2026-11-02', {}, timezone='UTC', now=m.instant('2026-11-02T10:03:00Z'))
        self.assertEqual(report['recovery']['lastKnownSessionState'], 'resumed')
        self.assertEqual(report['recovery']['currentSessionState'], 'unknown')
        report = m.review([stopped,heartbeat], '2026-11-02', {}, timezone='UTC', now=m.instant('2026-11-02T10:03:00Z'))
        self.assertEqual(report['recovery']['lastKnownSessionState'], 'stopped')

    def test_revoked_permission_produces_source_health(self):
        with patch.object(pathlib.Path, 'read_text', side_effect=PermissionError('private')):
            rows, health = m.read_lines(pathlib.Path('/not-read'))
            self.assertEqual(rows, [])
            self.assertEqual(health['state'], 'unreadable')
            self.assertEqual(health['reason'], 'permission_denied')
            self.assertNotIn('private', str(health))

    def test_context_tool_switches_union_without_focus_or_reading_claim(self):
        one = event(start='2026-11-02T09:00:00Z',end='2026-11-02T09:20:00Z')
        two = event(start='2026-11-02T09:10:00Z',end='2026-11-02T09:30:00Z')
        for row,bundle in ((one,'fixture.editor'),(two,'fixture.reader')):
            row.update(type='health',evidence='activity',metrics={'focusSeconds':None},
                       context={'collector':'frontmost_bundle','observedSeconds':1200,'bundleId':bundle,'unknownGapSeconds':None})
        report = self.report([one,two])
        self.assertEqual(report['contextObservation']['secondsByCategory']['work'],1800)
        self.assertEqual(report['contextObservation']['secondsBySource']['mac']['work'],1800)
        self.assertIsNone(report['categories']['work']['focusHoursByEvidence']['activity'])
        self.assertEqual(report['contextObservation']['readingAssessment'],'unknown')
        self.assertNotIn('fixture.editor',str(report))

    def test_context_unknown_gap_is_separate_from_observed_time(self):
        row = event(category=None)
        row.update(type='health',evidence='activity',metrics={'focusSeconds':None},
                   context={'collector':'frontmost_bundle','observedSeconds':None,'unknownGapSeconds':3600,'signal':'wake'})
        report = self.report([row])
        self.assertIsNone(report['contextObservation']['observedUnionSeconds'])
        self.assertEqual(report['contextObservation']['unknownGapSecondsReported'],3600)
        self.assertIsNone(report['contextObservation']['coverageDenominator'])

    def test_cross_device_context_overlap_is_not_counted_twice(self):
        rows = [event(source='mac',category='work'),event(source='android',category='study')]
        for row in rows:
            row.update(metrics={'focusSeconds':None},context={'observedSeconds':3600})
        report = self.report(rows)
        self.assertEqual(report['contextObservation']['observedUnionSeconds'],3600)
        self.assertEqual(report['contextObservation']['secondsByCategory']['unknown'],3600)

    def test_common_contract_rejects_noncanonical_uuid(self):
        row = event()
        row['eventId'] = row['eventId'].replace('-','')
        with self.assertRaises(ValueError): m.validate(row)

    def test_future_interval_and_outcome_quarantined_not_zero(self):
        now=m.instant('2026-11-02T12:00:00Z')
        future=event(category='study',start='2026-11-02T12:00:00Z',end='2026-11-02T13:00:00Z')
        future['outcome']='future_result_fixture'
        report=m.review([future],'2026-11-02',{},timezone='UTC',now=now)
        self.assertEqual(report['clockDiagnostics']['futureEventCount'],1)
        self.assertIsNone(report['categories']['study']['focusHoursByEvidence']['timer'])
        self.assertEqual(report['outcomes'],[])
        self.assertIsNone(report['latestObservedAt'])

    def test_future_device_then_clock_rollback_preserves_new_safe_records(self):
        now=m.instant('2026-11-02T12:00:00Z')
        future=event(start='2030-11-02T09:00:00Z',end='2030-11-02T10:00:00Z')
        safe=event()
        report=m.review([future,safe],'2026-11-02',{},timezone='UTC',now=now)
        self.assertEqual(report['categories']['work']['focusHoursByEvidence']['timer'],1)
        self.assertEqual(report['clockDiagnostics']['futureEventCount'],1)
        self.assertEqual(m.instant(report['latestObservedAt']),m.instant(safe['occurredAt']))

    def test_future_long_span_cannot_leak_into_earlier_day(self):
        future=event(start='2026-11-02T00:00:00Z',end='2026-11-03T00:00:00Z')
        future['metrics']['focusSeconds']=86400
        report=m.review([future],'2026-11-02',{},timezone='UTC',now=m.instant('2026-11-02T12:00:00Z'))
        self.assertIsNone(report['categories']['work']['focusHoursByEvidence']['timer'])

    def test_negative_and_local_timestamp_rejected(self):
        row = event()
        row['metrics']['focusSeconds'] = -1
        with self.assertRaises(ValueError): m.validate(row)
        row = event()
        row['occurredAt'] = '2026-11-02T10:00:00'
        with self.assertRaises(ValueError): m.validate(row)


if __name__ == '__main__': unittest.main()
