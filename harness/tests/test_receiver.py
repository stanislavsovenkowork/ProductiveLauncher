import datetime as dt
import http.client
import json
import pathlib
import sys
import tempfile
import threading
import unittest
import uuid
from unittest.mock import patch

sys.path.insert(0,str(pathlib.Path(__file__).parents[1]))
import receiver


def event(source='android'):
    now = dt.datetime.now(dt.timezone.utc)
    return {'schemaVersion':1,'eventId':str(uuid.uuid4()),'sessionId':str(uuid.uuid4()),
            'occurredAt':now.isoformat(),'source':source,'category':'study','type':'completed',
            'evidence':'timer','metrics':{'focusSeconds':60},'startedAt':(now-dt.timedelta(seconds=60)).isoformat(),
            'endedAt':now.isoformat()}


class StoreTests(unittest.TestCase):
    def test_idempotent_and_conflicts_atomic(self):
        with tempfile.TemporaryDirectory() as directory:
            store = receiver.EventStore(directory)
            row = event()
            self.assertEqual(store.ingest([row]),{'accepted':1,'duplicates':0})
            self.assertEqual(store.ingest([row]),{'accepted':0,'duplicates':1})
            other = dict(row,category='work')
            with self.assertRaises(receiver.ConflictError):store.ingest([event(),other])
            self.assertEqual(store.health()['sources']['android']['events'],1)
            self.assertEqual((pathlib.Path(directory)/'android-events.jsonl').stat().st_mode & 0o777,0o600)

    def test_invalid_batch_and_cross_source_conflict(self):
        with tempfile.TemporaryDirectory() as directory:
            store = receiver.EventStore(directory)
            row = event()
            with self.assertRaises(ValueError):store.ingest([row,event('mac')])
            store.ingest([row])
            with self.assertRaises(receiver.ConflictError):store.ingest([dict(row,source='mac')])
            with self.assertRaises(ValueError):store.ingest([dict(row,metrics={'focusSeconds':float('nan')})])

    def test_prepare_private_token_and_preserves_existing_config(self):
        with tempfile.TemporaryDirectory() as directory:
            result = receiver.prepare(directory)
            token_path = pathlib.Path(result['tokenFile'])
            self.assertGreater(len(token_path.read_text().strip()),32)
            self.assertEqual(token_path.stat().st_mode & 0o777,0o600)
            self.assertNotIn(token_path.read_text().strip(),str(result))
            with self.assertRaises(FileExistsError):receiver.prepare(directory)
            self.assertFalse(result['active'])

    def test_targeted_session_removal_preserves_other_sessions(self):
        with tempfile.TemporaryDirectory() as directory:
            store = receiver.EventStore(directory)
            target, other = event(), event()
            store.ingest([target,other])
            result = store.remove_session(target['sessionId'].upper())
            self.assertEqual(result['removedBySource']['android'],1)
            rows = store.read()['android']
            self.assertEqual([r['sessionId'] for r in rows],[other['sessionId']])
            self.assertEqual(len(list((pathlib.Path(directory)/'.maintenance').glob('*.jsonl'))),1)
            self.assertEqual(store.remove_session(target['sessionId'])['removedBySource']['android'],0)

    def test_manifest_refuses_tampering_and_public_bind(self):
        with tempfile.TemporaryDirectory() as directory:
            result = receiver.prepare(directory)
            config = json.loads(pathlib.Path(result['config']).read_text())
            receiver.verify_runtime(config)
            config['host'] = '0.0.0.0'
            pathlib.Path(result['config']).write_text(json.dumps(config))
            with self.assertRaises(ValueError):receiver.serve(result['config'])
            (pathlib.Path(directory)/'runtime/receiver.py').write_text('# changed')
            with self.assertRaises(ValueError):receiver.verify_runtime(config)

    def test_install_uninstall_owns_only_its_job(self):
        with tempfile.TemporaryDirectory() as directory:
            home = pathlib.Path(directory)/'home'
            private = pathlib.Path(directory)/'private'
            receiver.prepare(str(private))
            fake_result = receiver.subprocess.CompletedProcess([],0,stdout='loaded',stderr='')
            with patch.object(pathlib.Path,'home',return_value=home), patch.object(receiver.subprocess,'run',return_value=fake_result):
                result = receiver.service_install(str(private))
                self.assertTrue(result['loaded'])
                with self.assertRaises(FileExistsError):receiver.service_install(str(private))
                result = receiver.service_uninstall(str(private))
                self.assertFalse(result['installed'])
                self.assertTrue((private/'receiver.token').exists())

    def test_future_upload_diagnostic_does_not_block_clock_restored_records(self):
        with tempfile.TemporaryDirectory() as directory:
            store=receiver.EventStore(directory)
            future=event()
            for key in ('occurredAt','startedAt','endedAt'):
                future[key]=(receiver.VALIDATOR.timestamp(future[key])+dt.timedelta(days=365)).isoformat()
            self.assertEqual(store.ingest([future])['accepted'],1)
            safe=event()
            self.assertEqual(store.ingest([safe])['accepted'],1)
            now=dt.datetime.now(dt.timezone.utc)+dt.timedelta(seconds=1)
            health=store.health(now=now)['sources']['android']
            self.assertEqual(health['futureEventCount'],1)
            self.assertEqual(health['eventFreshness'],'clock_skew')
            self.assertEqual(receiver.VALIDATOR.timestamp(health['latestObservedAt']),receiver.VALIDATOR.timestamp(safe['occurredAt']))
            self.assertEqual(store.health(now=now)['sources']['android']['events'],2)

    def test_future_record_retention_is_bounded_by_server_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            store=receiver.EventStore(directory)
            future=event()
            for key in ('occurredAt','startedAt','endedAt'):
                future[key]=(receiver.VALIDATOR.timestamp(future[key])+dt.timedelta(days=365)).isoformat()
            store.ingest([future])
            first=store.health()['sources']['android']
            self.assertIsNotNone(first['lastSuccessfulReceiptAt'])
            self.assertEqual(first['futureEventCount'],1)
            later=dt.datetime.now(dt.timezone.utc)+dt.timedelta(days=91)
            self.assertEqual(store.health(now=later)['sources']['android']['events'],0)

    def test_upgrade_preserves_token_and_inbox_with_rollback(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);private=root/'private';home=root/'home'
            generated=receiver.prepare(str(private))
            token=pathlib.Path(generated['tokenFile']).read_bytes()
            row=event();store=receiver.EventStore(generated['inbox']);store.ingest([row])
            fake=receiver.subprocess.CompletedProcess([],0,stdout='loaded',stderr='')
            with patch.object(pathlib.Path,'home',return_value=home),patch.object(receiver.subprocess,'run',return_value=fake):
                receiver.service_install(str(private))
                self.assertTrue(receiver.service_upgrade(str(private))['upgraded'])
            config_before=(private/'receiver-config.json').read_bytes()
            calls={'bootstrap':0}
            def fail_once(command,**kwargs):
                if 'bootstrap' in command:
                    calls['bootstrap']+=1
                    if calls['bootstrap']==1:raise receiver.subprocess.CalledProcessError(1,command)
                return fake
            with patch.object(pathlib.Path,'home',return_value=home),patch.object(receiver.subprocess,'run',side_effect=fail_once):
                with self.assertRaises(ValueError):receiver.service_upgrade(str(private))
            self.assertEqual((private/'receiver-config.json').read_bytes(),config_before)
            self.assertEqual(pathlib.Path(generated['tokenFile']).read_bytes(),token)
            self.assertEqual(store.read()['android'],[row])

    def test_retention_rejects_old_upload(self):
        with tempfile.TemporaryDirectory() as directory:
            row = event()
            row['occurredAt'] = (dt.datetime.now(dt.timezone.utc)-dt.timedelta(days=100)).isoformat()
            with self.assertRaises(ValueError):receiver.EventStore(directory).ingest([row])


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.token = 'synthetic-token-'+'x'*48
        self.server = receiver.Receiver(receiver.EventStore(self.directory.name),self.token,0)
        self.thread = threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.directory.cleanup()

    def request(self,method,path,body=None,auth=True,headers=None):
        request_headers = {'Content-Type':'application/json'}
        if auth:request_headers['Authorization'] = 'Bearer '+self.token
        if headers:request_headers.update(headers)
        connection = http.client.HTTPConnection(*self.server.server_address,timeout=5)
        connection.request(method,path,body,request_headers)
        response = connection.getresponse()
        data = json.loads(response.read())
        connection.close()
        return response.status,data

    def test_loopback_auth_and_health(self):
        self.assertEqual(self.server.server_address[0],'127.0.0.1')
        self.assertEqual(self.request('GET','/v1/health',auth=False)[0],401)
        status,data = self.request('GET','/v1/health')
        self.assertEqual(status,200)
        self.assertEqual(data['sources']['android']['events'],0)
        self.assertNotIn('taskRef',str(data))

    def test_ingestion_retry_and_conflict(self):
        row = event()
        body = json.dumps({'schemaVersion':1,'events':[row]})
        self.assertEqual(self.request('POST','/v1/events',body),(202,{'accepted':1,'duplicates':0}))
        self.assertEqual(self.request('POST','/v1/events',body),(202,{'accepted':0,'duplicates':1}))
        body = json.dumps({'schemaVersion':1,'events':[dict(row,category='work')]})
        self.assertEqual(self.request('POST','/v1/events',body)[0],409)

    def test_limits_and_bad_payload(self):
        self.assertEqual(self.request('POST','/v1/events','{}',headers={'Content-Length':str(receiver.MAX_BODY+1)})[0],413)
        self.assertEqual(self.request('POST','/v1/events','not json')[0],400)
        self.assertEqual(self.request('POST','/v1/events','{}',headers={'Content-Type':'text/plain'})[0],415)
        self.assertEqual(self.request('POST','/v1/events','{}',auth=False)[0],401)
        self.assertEqual(self.request('POST','/v1/events',json.dumps({'schemaVersion':1,'events':[event()] * 257}))[0],400)


if __name__ == '__main__':unittest.main()
