import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest
import uuid

spec=importlib.util.spec_from_file_location('vault_events',pathlib.Path(__file__).parents[1]/'vault_events.py')
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)


def event():
    return {'schemaVersion':1,'eventId':str(uuid.uuid4()),'sessionId':None,'occurredAt':'2026-10-28T12:00:00Z',
            'source':'android','category':'study','type':'health','evidence':'activity',
            'metrics':{'focusSeconds':None,'distractionSeconds':None,'maintenanceSeconds':None}}


def write(root,rows,producer=None):
    directory=root/(producer or str(uuid.uuid4()));directory.mkdir(parents=True,exist_ok=True)
    data=(''.join(json.dumps(row,separators=(',',':'))+'\n' for row in rows)).encode()
    path=directory/(hashlib.sha256(data).hexdigest()+'.jsonl');path.write_bytes(data)
    return path


class VaultTests(unittest.TestCase):
    def root(self,directory):return pathlib.Path(directory)/'FocusDeck/Telemetry/v1/events/android'
    def test_window_relief_and_rest_reasons_survive_without_focus_or_private_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            root=self.root(directory); window=str(uuid.uuid4())
            events=[dict(event(),reason=reason,protectionId=window,evidence='verified')
                    for reason in ('bounded_relief_started','rest_without_feeds','rest_returned')]
            path=write(root,events)
            self.assertEqual(v.read_batch(path),events)
            rows,health=v.collect(root)
            self.assertEqual(rows,sorted(events,key=lambda row:(row['occurredAt'],row['eventId'])))
            self.assertEqual(health['state'],'observed')
            self.assertTrue(all(row['metrics']['focusSeconds'] is None for row in rows))
            unsafe=dict(event(),reason='bounded_relief_started',url='https://private.example/')
            with self.assertRaises(ValueError):v.read_batch(write(root,[unsafe]))
            self.assertNotIn('private.example',str(rows))
    def test_window_started_preserves_unknown_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root=self.root(directory)
            row=dict(event(), reason='window_started', evidence='unknown', protectionId=str(uuid.uuid4()))
            write(root,[row])
            rows,health=v.collect(root)
            self.assertEqual(rows,[row])
            self.assertEqual(health['state'],'observed')
            self.assertEqual(rows[0]['evidence'],'unknown')

    def test_valid_batch_only_explicit_namespace(self):
        with tempfile.TemporaryDirectory() as directory:
            root=self.root(directory);one=event();write(root,[one])
            (root.parent/'not-a-note.txt').write_text('private_fixture_never_read')
            rows,health=v.collect(root)
            self.assertEqual(rows,[one]);self.assertEqual(health['state'],'observed')
            self.assertNotIn('private_fixture',str(rows)+str(health))
            with self.assertRaises(ValueError):v.collect(pathlib.Path(directory))
    def test_partial_hash_and_temporary_files_never_counted(self):
        with tempfile.TemporaryDirectory() as directory:
            root=self.root(directory);path=write(root,[event()]);path.write_bytes(b'partial')
            (path.parent/'.syncthing.transfer.tmp').write_bytes(b'partial')
            rows,health=v.collect(root)
            self.assertEqual(rows,[]);self.assertEqual(health['state'],'partial')
            self.assertEqual(health['invalidBatches'],1)
            self.assertEqual(health['ignoredTemporaryOrConflictFiles'],1)
    def test_restart_duplicates_and_conflicts(self):
        with tempfile.TemporaryDirectory() as directory:
            root=self.root(directory);one=event();write(root,[one]);write(root,[one])
            rows,health=v.collect(root)
            self.assertEqual(len(rows),1);self.assertEqual(health['duplicateEvents'],1)
            self.assertEqual(v.collect(root)[0],rows)
            write(root,[dict(one,category='work')])
            rows,health=v.collect(root)
            self.assertEqual(rows,[]);self.assertEqual(health['conflictingEventIds'],1)
    def test_privacy_source_and_event_limits_reject_entire_batch(self):
        for transform in (lambda row:dict(row,title='private_fixture'),lambda row:dict(row,source='mac'),lambda row:dict(row,taskRef='private phrase')):
            with tempfile.TemporaryDirectory() as directory:
                root=self.root(directory);write(root,[event(),transform(event())])
                self.assertEqual(v.collect(root)[0],[])
        with tempfile.TemporaryDirectory() as directory:
            root=self.root(directory);write(root,[event() for _ in range(257)])
            self.assertEqual(v.collect(root)[1]['invalidBatches'],1)
    def test_missing_unreadable_and_symlink_are_unknown_not_work(self):
        with tempfile.TemporaryDirectory() as directory:
            root=self.root(directory)
            self.assertEqual(v.collect(root)[1]['state'],'missing')
            root.mkdir(parents=True)
            target=pathlib.Path(directory)/'outside';target.mkdir()
            (root/str(uuid.uuid4())).symlink_to(target,target_is_directory=True)
            rows,health=v.collect(root)
            self.assertEqual(rows,[]);self.assertEqual(health['state'],'partial')
    def test_unknown_reason_and_alternate_copy_do_not_block_valid_heartbeat(self):
        with tempfile.TemporaryDirectory() as directory:
            root=self.root(directory)
            first=event();first['reason']='private_free_text_must_not_copy'
            heartbeat=event();heartbeat['type']='heartbeat'
            path=write(root,[first,heartbeat])
            complete=path.read_bytes()
            path.write_bytes(b'partial preserved')
            alternate=path.with_name(path.stem+'.retry1.jsonl');alternate.write_bytes(complete)
            rows,health=v.collect(root)
            self.assertEqual(len(rows),2)
            self.assertEqual(health['validBatches'],1)
            self.assertEqual(health['invalidBatches'],1)
            self.assertTrue(all('reason' not in row for row in rows))
            self.assertNotIn('private_free_text',str(rows))
            self.assertEqual(path.read_bytes(),b'partial preserved')

    def test_complete_transfer_after_partial_and_peer_absence(self):
        with tempfile.TemporaryDirectory() as directory:
            root=self.root(directory);row=event();path=write(root,[row]);complete=path.read_bytes()
            path.write_bytes(complete[:20])
            self.assertEqual(v.collect(root)[0],[])
            path.write_bytes(complete)
            self.assertEqual(v.collect(root)[0],[row])
            # Existing valid local receipt stays valid while peer is disconnected/asleep.
            self.assertEqual(v.collect(root)[0],[row])


if __name__=='__main__':unittest.main()
