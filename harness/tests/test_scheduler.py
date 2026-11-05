import datetime as dt
import json
import pathlib
import plistlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parents[1]))
import scheduler


class SchedulerTests(unittest.TestCase):
    def test_prepare_is_disabled_and_plist_uses_absolute_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            result = scheduler.prepare(directory, [directory+'/missing.jsonl'])
            self.assertFalse(result['active'])
            config = json.loads(pathlib.Path(result['config']).read_text())
            self.assertFalse(config['enabledByUser'])
            with open(result['plist'], 'rb') as stream:
                job = plistlib.load(stream)
            self.assertTrue(pathlib.Path(job['ProgramArguments'][0]).is_absolute())
            self.assertTrue(job['RunAtLoad'])
            with self.assertRaises(ValueError): scheduler.run(result['config'])

    def test_private_output_outside_repository_and_absolute_inputs(self):
        with self.assertRaises(ValueError): scheduler.private_directory(scheduler.REPOSITORY/'reports')
        with self.assertRaises(ValueError): scheduler.absolute('relative.jsonl')

    def test_prepare_rejects_launchd_installation_destination(self):
        with self.assertRaises(ValueError):
            scheduler.private_directory('/tmp/LaunchAgents/review')

    def test_runtime_survives_independent_process_restart_and_detects_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            generated = scheduler.prepare(directory, [directory+'/missing.jsonl'])
            config_path = pathlib.Path(generated['config'])
            config = json.loads(config_path.read_text())
            config['enabledByUser'] = True
            config_path.write_text(json.dumps(config))
            command = [sys.executable,str(pathlib.Path(generated['runtime'])/'scheduler.py'),'run','--config',str(config_path)]
            for _ in range(2):
                scheduler.subprocess.run(command,check=True,capture_output=True,text=True)
            report = json.loads((pathlib.Path(directory)/'reports/latest.json').read_text())
            self.assertEqual(report['dailyCheck']['state'],'no_data')
            self.assertEqual(report['runtimeIntegrity']['state'],'verified')
            (pathlib.Path(generated['runtime'])/'metrics.py').write_text('# modified')
            self.assertIn('metrics.py',scheduler.runtime_integrity(config)['changed'])

    def test_existing_weekly_refreshes_after_protection_opt_in(self):
        import uuid
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory)
            generated=scheduler.prepare(str(root/'private'), [])
            config_path=pathlib.Path(generated['config'])
            config=json.loads(config_path.read_text()); config['enabledByUser']=True
            config['timezone']='UTC'; config_path.write_text(json.dumps(config))
            now=dt.datetime(2026,11,9,12,tzinfo=dt.timezone.utc)
            scheduler.run(str(config_path),now=now)
            weekly=root/'private/reports/weekly-2026-11-09.json'
            self.assertEqual(json.loads(weekly.read_text())['protectionReview']['state'],'not_enabled')
            source=root/'window-events-v2.jsonl'
            row={'event':'relief_started','windowId':str(uuid.uuid4()),'eventId':str(uuid.uuid4()),
                 'at':'2026-11-03T12:00:00Z','policyVersion':2,'commitStatus':'committed',
                 'policyRevisionId':str(uuid.uuid4())}
            source.write_text(json.dumps(row)+'\n'); original=source.read_bytes()
            config.update(protectionReviewEnabled=True,windowEventPaths=[str(source),str(root/'missing.jsonl')])
            config_path.write_text(json.dumps(config))
            scheduler.run(str(config_path),now=now)
            report=json.loads(weekly.read_text()); protection=report['protectionReview']
            self.assertEqual(protection['counts']['relief'],1)
            self.assertEqual(protection['sourceStates'],['observed','unknown'])
            self.assertEqual(protection['state'],'observed_incomplete')
            self.assertIsNone(protection['reliefSeconds'])
            self.assertEqual(report['nextReviewDate'],'2026-11-16')
            self.assertEqual(report['adaptation'],'none')
            self.assertEqual(source.read_bytes(),original)

    def test_weekly_review_missing_days_are_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory)
            result = scheduler.weekly_review(output, dt.date(2026,11,9), {'state':'not_configured'})
            self.assertEqual(result['reportsPresent'],0)
            self.assertEqual(result['measurementCoverageFraction'],0)
            self.assertIsNone(result['focusHours']['work']['timer'])
            self.assertEqual(result['decision'],'insufficient_for_automatic_benefit_claim')

    def test_prepare_preserves_existing_config(self):
        with tempfile.TemporaryDirectory() as directory:
            generated = scheduler.prepare(directory, [])
            before = pathlib.Path(generated['config']).read_bytes()
            with self.assertRaises(FileExistsError): scheduler.prepare(directory, [])
            self.assertEqual(pathlib.Path(generated['config']).read_bytes(), before)

    def test_weekly_review_corrupt_report_does_not_crash(self):
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory)
            (output/'2026-11-02.json').write_text('{corrupt')
            result = scheduler.weekly_review(output,dt.date(2026,11,9),{})
            self.assertIn('2026-11-02',result['invalidReportDays'])
            self.assertEqual(result['reportsPresent'],0)

    def test_current_day_partial_report_uses_local_day(self):
        with tempfile.TemporaryDirectory() as directory:
            generated = scheduler.prepare(directory,[directory+'/missing.jsonl'])
            config = json.loads(pathlib.Path(generated['config']).read_text())
            result = scheduler.current_report(config,now=dt.datetime(2026,11,2,22,tzinfo=dt.timezone.utc))
            report = json.loads(pathlib.Path(result['report']).read_text())
            self.assertEqual(report['day'],'2026-11-03')
            self.assertTrue(report['isPartialDay'])
            self.assertEqual(report['period'],'current_local_day_so_far')
            self.assertEqual(report['dailyCheck']['state'],'no_data')
            self.assertEqual(report['dailyCheck']['sourceHealth']['events0']['freshness'],'no_data')

    def test_upgrade_preserves_sources_and_rolls_back_on_reload_error(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            private = pathlib.Path(directory)/'private'
            home = pathlib.Path(directory)/'home'
            generated = scheduler.prepare(str(private),[directory+'/custom-source.jsonl'])
            config_path = pathlib.Path(generated['config'])
            config_before = config_path.read_bytes()
            fake = scheduler.subprocess.CompletedProcess([],0,stdout='loaded',stderr='')
            with patch.object(pathlib.Path,'home',return_value=home),patch.object(scheduler.subprocess,'run',return_value=fake):
                scheduler.service_install(str(private))
                installed_config = config_path.read_bytes()
                result = scheduler.service_upgrade(str(private))
                self.assertTrue(result['upgraded'])
                config = json.loads(config_path.read_text())
                self.assertEqual(config['events'],[str(pathlib.Path(directory+'/custom-source.jsonl').resolve())])
                self.assertEqual(scheduler.runtime_integrity(config)['state'],'verified')
                original_hashes = config['runtimeHashes'].copy()
            calls = {'bootstrap':0}
            def failure_once(command,**kwargs):
                if 'bootstrap' in command:
                    calls['bootstrap'] += 1
                    if calls['bootstrap'] == 1:
                        raise scheduler.subprocess.CalledProcessError(1,command)
                return fake
            before_failure = config_path.read_bytes()
            with patch.object(pathlib.Path,'home',return_value=home),patch.object(scheduler.subprocess,'run',side_effect=failure_once):
                with self.assertRaises(ValueError):scheduler.service_upgrade(str(private))
            self.assertEqual(config_path.read_bytes(),before_failure)
            self.assertEqual(scheduler.runtime_integrity(json.loads(config_path.read_text()))['state'],'verified')

    def test_future_clock_does_not_make_source_recent(self):
        now=dt.datetime(2026,11,2,12,tzinfo=dt.timezone.utc)
        report={'latestObservedAt':'2026-11-02T11:00:00+00:00',
                'clockDiagnostics':{'futureEventCount':1},
                'health':{'events0':{'latestObservedAt':'2026-11-02T11:00:00+00:00',
                                    'latestReportedAt':'2030-11-02T11:00:00+00:00','futureEventCount':1}}}
        result=scheduler.freshness_status(report,{'staleAfterHours':36,'events':['fixture']},now)
        self.assertEqual(result['state'],'clock_skew')
        self.assertEqual(result['sourceHealth']['events0']['freshness'],'clock_skew')
        self.assertEqual(result['sourceHealth']['events0']['ageHours'],1)
        self.assertGreater(result['sourceHealth']['events0']['clockSkewSeconds'],86400)
        self.assertEqual(result['sourcesNeedingAttention'],1)
        legacy={'latestObservedAt':'2030-11-02T11:00:00+00:00','health':{'events0':{'latestObservedAt':'2030-11-02T11:00:00+00:00'}}}
        result=scheduler.freshness_status(legacy,{'staleAfterHours':36,'events':['fixture']},now)
        self.assertEqual(result['state'],'clock_skew')
        self.assertIsNone(result['latestAgeHours'])

    def test_weekly_rejects_legacy_or_future_evaluated_measurements(self):
        from metrics import review
        with tempfile.TemporaryDirectory() as directory:
            output=pathlib.Path(directory)
            now=dt.datetime(2026,11,9,12,tzinfo=dt.timezone.utc)
            safe=review([],'2026-11-08',{},now=now)
            (output/'2026-11-08.json').write_text(json.dumps(safe))
            legacy=dict(safe)
            legacy.pop('clockDiagnostics')
            legacy['categories']['work']['focusHoursByEvidence']['timer']=99
            (output/'2026-11-07.json').write_text(json.dumps(legacy))
            future=dict(safe,evaluatedAtUTC='2030-11-09T12:00:00+00:00')
            (output/'2026-11-06.json').write_text(json.dumps(future))
            result=scheduler.weekly_review(output,dt.date(2026,11,9),{},now=now)
            self.assertEqual(result['reportsPresent'],1)
            self.assertIn('2026-11-07',result['invalidReportDays'])
            self.assertIn('2026-11-06',result['invalidReportDays'])
            self.assertIsNone(result['focusHours']['work']['timer'])

    def test_current_report_future_and_clock_restored_roundtrip(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory)
            events=root/'fixture.jsonl'
            row={'schemaVersion':1,'eventId':'cf6fa376-4b24-4490-a66e-3c5ea1146ba4','sessionId':None,
                 'occurredAt':'2026-11-02T10:00:00Z','source':'android','category':'work','type':'completed',
                 'evidence':'timer','metrics':{'focusSeconds':3600},'startedAt':'2026-11-02T09:00:00Z','endedAt':'2026-11-02T10:00:00Z'}
            future=dict(row,eventId='8af0bc9d-2250-450b-bb96-71f27247edaa',occurredAt='2030-11-02T10:00:00Z',
                        startedAt='2030-11-02T09:00:00Z',endedAt='2030-11-02T10:00:00Z')
            events.write_text(json.dumps(future)+'\n'+json.dumps(row)+'\n')
            generated=scheduler.prepare(str(root/'private'),[str(events)])
            config=json.loads(pathlib.Path(generated['config']).read_text())
            result=scheduler.current_report(config,now=dt.datetime(2026,11,2,12,tzinfo=dt.timezone.utc))
            report=json.loads(pathlib.Path(result['report']).read_text())
            self.assertEqual(report['dailyCheck']['state'],'clock_skew')
            self.assertEqual(report['clockDiagnostics']['futureEventCount'],1)
            self.assertEqual(report['categories']['work']['focusHoursByEvidence']['timer'],1)

    def test_syncthing_batch_current_review_and_missing_source_cache(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory)
            namespace=root/'vault/FocusDeck/Telemetry/v1/events/android'
            producer=namespace/'cf6fa376-4b24-4490-a66e-3c5ea1146ba4';producer.mkdir(parents=True)
            row={'schemaVersion':1,'eventId':'8af0bc9d-2250-450b-bb96-71f27247edaa','sessionId':None,
                 'occurredAt':'2026-11-02T10:00:00Z','source':'android','category':'work','type':'completed',
                 'evidence':'timer','metrics':{'focusSeconds':3600},'startedAt':'2026-11-02T09:00:00Z','endedAt':'2026-11-02T10:00:00Z'}
            data=(json.dumps(row)+'\n').encode();batch=producer/(hashlib.sha256(data).hexdigest()+'.jsonl');batch.write_bytes(data)
            generated=scheduler.prepare(str(root/'private'),[],vault_roots=[str(namespace)])
            config=json.loads(pathlib.Path(generated['config']).read_text())
            now=dt.datetime(2026,11,2,12,tzinfo=dt.timezone.utc)
            result=scheduler.current_report(config,now=now)
            report=json.loads(pathlib.Path(result['report']).read_text())
            self.assertEqual(report['categories']['work']['focusHoursByEvidence']['timer'],1)
            self.assertEqual(report['vaultSyncHealth']['vaultSync0']['state'],'observed')
            moved=root/'temporarily-unavailable';namespace.rename(moved)
            result=scheduler.current_report(config,now=now)
            report=json.loads(pathlib.Path(result['report']).read_text())
            self.assertTrue(report['vaultSyncHealth']['vaultSync0']['cachedPriorReceipt'])
            self.assertEqual(report['vaultSyncHealth']['vaultSync0']['state'],'missing')
            self.assertEqual(report['categories']['work']['focusHoursByEvidence']['timer'],1)

    def test_missing_namespace_twice_does_not_invent_cached_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory)
            output=scheduler.private_directory(str(root/'reports'))
            config={'vaultTelemetryRoots':[str(root/'missing/events/android')]}
            for _ in range(2):
                paths,health=scheduler.collect_vault_sources(config,output)
                self.assertEqual(health['vaultSync0']['state'],'missing')
                self.assertFalse(health['vaultSync0'].get('cachedPriorReceipt',False))
                self.assertEqual(pathlib.Path(paths[0]).read_bytes(),b'')

    def test_no_data_daily_check_and_due_protocol(self):
        with tempfile.TemporaryDirectory() as directory:
            protocol = pathlib.Path(directory)/'protocol.json'
            protocol.write_text(json.dumps({'startDate':'2026-11-01', 'endDate':'2026-11-02'}))
            generated = scheduler.prepare(directory, [directory+'/missing.jsonl'], protocol=str(protocol))
            path = pathlib.Path(generated['config'])
            config = json.loads(path.read_text())
            config['enabledByUser'] = True
            path.write_text(json.dumps(config))
            result = scheduler.run(str(path), now=dt.datetime(2026,11,3,8,tzinfo=dt.timezone.utc))
            report = json.loads(pathlib.Path(result['report']).read_text())
            self.assertEqual(report['dailyCheck']['state'], 'no_data')
            self.assertEqual(report['dailyCheck']['sourceHealth']['events0']['freshness'], 'no_data')
            self.assertTrue(report['protocolReview']['reviewDue'])
            self.assertFalse(report['protocolReview']['experimentReady'])
            self.assertEqual(report['day'], '2026-11-02')
            self.assertEqual(pathlib.Path(result['report']).stat().st_mode & 0o777, 0o600)


if __name__ == '__main__': unittest.main()

class ProtectionReviewTests(unittest.TestCase):
    def test_opt_in_window_events_and_unknown_sources(self):
        import json, tempfile, uuid
        from pathlib import Path
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'window-events-v2.jsonl'
            now=dt.datetime(2026,11,9,tzinfo=dt.timezone.utc)
            cfg={'protectionReviewEnabled':True,'windowEventPaths':[str(path)],'timezone':'UTC'}
            missing=scheduler.protection_review(cfg,[],{},dt.date(2026,11,2),dt.date(2026,11,9),now)
            self.assertIsNone(missing['counts'])
            row={'event':'relief_started','windowId':str(uuid.uuid4()),'eventId':str(uuid.uuid4()),'at':'2026-11-03T12:00:00Z','policyVersion':2}
            path.write_text(json.dumps(row)+'\n'+json.dumps(row)+'\n')
            result=scheduler.protection_review(cfg,[],{},dt.date(2026,11,2),dt.date(2026,11,9),now)
            self.assertEqual(result['counts']['relief'],1)
            self.assertEqual(result['policyVersion'],'unknown')
            self.assertIsNone(result['reliefSeconds'])
            path.write_text(json.dumps(dict(row,url='private'))+'\n')
            self.assertIsNone(scheduler.protection_review(cfg,[],{},dt.date(2026,11,2),dt.date(2026,11,9),now)['counts'])

    def test_android_explicit_return_excludes_automatic_expiry_and_outside_week(self):
        import json, tempfile, uuid
        from pathlib import Path
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'validated.jsonl'
            def event(reason, at):
                return {'schemaVersion':1, 'eventId':str(uuid.uuid4()), 'sessionId':None,
                        'occurredAt':at, 'source':'android', 'category':'study', 'type':'health',
                        'evidence':'verified', 'reason':reason,
                        'metrics':{'focusSeconds':None,'distractionSeconds':None,'maintenanceSeconds':None}}
            rows = [event('relief_ended_early','2026-11-02T00:00:00Z'),
                    event('rest_returned','2026-11-08T23:59:59Z'),
                    event('bounded_relief_expired','2026-11-03T12:00:00Z'),
                    event('relief_ended_early','2026-11-09T00:00:00Z'),
                    event('relief_ended_early','2026-11-01T23:59:59Z')]
            path.write_text(''.join(json.dumps(row)+'\n' for row in rows))
            result = scheduler.protection_review({'protectionReviewEnabled':True, 'timezone':'UTC'},
                [str(path)], {'vaultSync0':{'state':'observed'}}, dt.date(2026,11,2),
                dt.date(2026,11,9), dt.datetime(2026,11,9,tzinfo=dt.timezone.utc))
            self.assertEqual(result['counts']['return'], 2)
            self.assertEqual(result['eventTimesUTC']['return'], ['2026-11-02T00:00:00Z','2026-11-08T23:59:59Z'])
            self.assertIn('not resumed work', result['meaning'])

    def test_mac_revision_requires_confirmed_snapshot_and_valid_fields(self):
        import json, tempfile, uuid
        from pathlib import Path
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'window-events-v2.jsonl'
            revision=str(uuid.uuid4())
            row={'event':'configured','windowId':str(uuid.uuid4()),'eventId':str(uuid.uuid4()),
                 'at':'2026-11-03T12:00:00Z','policyVersion':2,
                 'policyRevisionId':revision,'commitStatus':'committed'}
            cfg={'protectionReviewEnabled':True,'windowEventPaths':[str(path)],'timezone':'UTC'}
            def review(rows):
                path.write_text(''.join(json.dumps(item)+'\n' for item in rows))
                return scheduler.protection_review(cfg,[],{},dt.date(2026,11,2),dt.date(2026,11,9),dt.datetime(2026,11,9,tzinfo=dt.timezone.utc))
            result=review([row,row])
            self.assertEqual(result['committedPolicyRevisionIds'],[revision])
            self.assertEqual(result['policyVersion'],'unknown')
            self.assertEqual(result['revisionHistoryCompleteness'],'unknown')
            self.assertEqual(review([{k:v for k,v in row.items() if k!='commitStatus'}])['committedPolicyRevisionIds'],[])
            self.assertIsNone(review([dict(row,policyRevisionId='invalid')])['counts'])
            self.assertIsNone(review([dict(row,commitStatus='proposal')])['counts'])
