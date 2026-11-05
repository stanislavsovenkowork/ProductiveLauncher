import datetime as dt
import pathlib
import tempfile
import unittest
from harness.recovery_reminder import evaluate

UTC=dt.timezone.utc
NOW=dt.datetime(2026,10,29,9,tzinfo=UTC)

class ReminderTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.path=pathlib.Path(self.tmp.name); self.sent=[]
        self.report={'enabled':True,'state':'source_unavailable','evaluatedAtUTC':NOW.isoformat(),
                     'lastObservedAtUTC':(NOW-dt.timedelta(hours=5)).isoformat(),
                     'context':{'taskRef':'task-1','occurredAtUTC':(NOW-dt.timedelta(hours=5)).isoformat()}}
    def call(self, **kw):
        args=dict(opted_in=True,permission='authorized',recovery=self.report,next_action='Открыть пример',deliver=self.sent.append,now=NOW)
        args.update(kw); return evaluate(self.path,**args)
    def test_consent_permission_and_missing_evidence(self):
        self.assertEqual(self.call(opted_in=False)['state'],'disabled')
        self.assertEqual(self.call(permission='denied')['state'],'permission_required')
        self.assertEqual(self.call(recovery={'enabled':True,'state':'source_unavailable'})['state'],'insufficient_evidence')
        self.assertEqual(self.call(next_action='')['state'],'insufficient_evidence')
        self.assertFalse(self.sent)
    def test_reservation_cooldown_and_restart(self):
        self.assertEqual(self.call()['state'],'dispatch_attempted')
        self.assertEqual(self.call()['state'],'cooldown')
        self.assertEqual(len(self.sent),1)
        self.assertNotIn('Открыть пример',(self.path/'recovery-reminder-state.json').read_text())
        self.assertEqual(self.call(opted_in=False)['state'],'disabled')
    def test_quiet_and_crash_reservation(self):
        night=dt.datetime(2026,10,29,20,tzinfo=UTC)
        report=dict(self.report,evaluatedAtUTC=night.isoformat())
        self.assertEqual(self.call(now=night,recovery=report)['state'],'quiet_hours')
        def fail(_): raise RuntimeError('host stopped')
        self.assertEqual(self.call(deliver=fail)['state'],'host_error')
        self.assertEqual(self.call()['state'],'cooldown')
    def test_episode_limit_and_stale_report(self):
        for day in range(3):
            now=NOW+dt.timedelta(days=day)
            report=dict(self.report,evaluatedAtUTC=now.isoformat())
            self.assertEqual(self.call(now=now,recovery=report)['state'],'dispatch_attempted')
        now=NOW+dt.timedelta(days=3)
        self.assertEqual(self.call(now=now,recovery=dict(self.report,evaluatedAtUTC=now.isoformat()))['state'],'episode_limit')
        self.assertEqual(self.call(now=now,recovery=self.report)['state'],'insufficient_evidence')

if __name__=='__main__': unittest.main()

class SchedulerReminderTests(unittest.TestCase):
    def test_only_matching_task_uses_saved_note(self):
        import json
        from unittest.mock import patch
        import sys
        sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
        import scheduler
        class Input:
            def __init__(self): self.data=b''
            def write(self,data): self.data+=data
            def close(self): pass
        class Process:
            def __init__(self): self.stdin=Input()
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory); host=root/'host'; host.touch()
            app=root/'Example.app'; app.mkdir()
            context=root/'session.json'; context.write_text(json.dumps({'taskRef':'different','note':'private note'}))
            config_path=root/'config.json'; config_path.write_text('{}')
            config={'outputDirectory':str(root),'recoveryReminder':{'enabled':True,'hostExecutable':str(host),'contextPath':str(context),'openAppPath':str(app)}}
            report={'enabled':True,'state':'source_unavailable','evaluatedAtUTC':NOW.isoformat(),
                    'lastObservedAtUTC':(NOW-dt.timedelta(hours=5)).isoformat(),
                    'context':{'taskRef':'task-1','occurredAtUTC':(NOW-dt.timedelta(hours=5)).isoformat()}}
            process=Process()
            with patch.object(scheduler.subprocess,'run',return_value=scheduler.subprocess.CompletedProcess([],0,stdout='authorized\n')), patch.object(scheduler.subprocess,'Popen',return_value=process):
                result=scheduler.run_recovery_reminder(config,config_path,report,NOW)
            self.assertEqual(result['state'],'dispatch_attempted')
            payload=json.loads(process.stdin.data)
            self.assertNotIn('private note',payload['body'])
            self.assertEqual(payload['body'],'Откройте сохранённый контекст, если хотите продолжить.')
            self.assertEqual(payload['appPath'],str(app.resolve()))
            context.write_text(json.dumps({'taskRef':'task-1','note':'next action'}))
            next_now=NOW+dt.timedelta(days=1)
            report['evaluatedAtUTC']=next_now.isoformat()
            process=Process()
            with patch.object(scheduler.subprocess,'run',return_value=scheduler.subprocess.CompletedProcess([],0,stdout='authorized\n')), patch.object(scheduler.subprocess,'Popen',return_value=process):
                result=scheduler.run_recovery_reminder(config,config_path,report,next_now)
            self.assertEqual(result['state'],'dispatch_attempted')
            self.assertEqual(json.loads(process.stdin.data)['body'],'next action')

class ReminderControlTests(unittest.TestCase):
    def test_stop_disables_even_if_host_missing_and_permission_unknown(self):
        import json
        from harness.recovery_reminder import control
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory); config=root/'config.json'
            data={'outputDirectory':str(root),'recoveryReminder':{'enabled':True,'hostExecutable':str(root/'missing')}}
            config.write_text(json.dumps(data))
            self.assertEqual(control(config,'status')['permission'],'unknown')
            self.assertEqual(control(config,'stop'),{'enabled':False,'removal':'unknown'})
            self.assertFalse(json.loads(config.read_text())['recoveryReminder']['enabled'])
    def test_status_denied_and_stop_targets_host_only(self):
        import json
        from unittest.mock import patch
        from harness.recovery_reminder import control
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory); config=root/'config.json'; host=root/'host'; host.touch()
            config.write_text(json.dumps({'outputDirectory':str(root),'recoveryReminder':{'enabled':True,'hostExecutable':str(host)}}))
            import subprocess
            with patch('subprocess.run',return_value=subprocess.CompletedProcess([],0,stdout='denied\n')) as run:
                self.assertEqual(control(config,'status')['permission'],'denied')
                self.assertEqual(control(config,'stop')['removal'],'requested')
                self.assertEqual(run.call_args.args[0],[str(host),'stop'])

class ReminderPipelineTests(unittest.TestCase):
    def test_configure_run_stop_preserves_context(self):
        import hashlib, json, sys, uuid
        sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
        import scheduler, recovery
        from harness.recovery_reminder import configure, control
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory); source=root/'events/android'/str(uuid.uuid4()); source.mkdir(parents=True)
            stamp=NOW-dt.timedelta(hours=5)
            row={'schemaVersion':1,'eventId':str(uuid.uuid4()),'sessionId':str(uuid.uuid4()),'occurredAt':stamp.isoformat(),
                 'source':'android','category':'work','type':'started','evidence':'timer','metrics':{},'taskRef':'task-1'}
            data=(json.dumps(row)+'\n').encode(); (source/(hashlib.sha256(data).hexdigest()+'.jsonl')).write_bytes(data)
            generated=scheduler.prepare(str(root/'private'),[],vault_roots=[str(source.parent)],timezone='UTC')
            config_path=pathlib.Path(generated['config']); config=json.loads(config_path.read_text()); config['enabledByUser']=True
            config_path.write_text(json.dumps(config)); recovery.configure(config,True,0,4,NOW)
            host=root/'fake-host'; host.write_text('#!/bin/sh\ncase "$1" in status) echo authorized;; post) cat >/dev/null;; stop) exit 0;; esac\n');host.chmod(0o700)
            app=root/'FocusDeck.app'; app.mkdir(); context=root/'session.json';context.write_text(json.dumps({'taskRef':'task-1','note':'synthetic next'})); before=context.read_bytes()
            configure(config_path,host=str(host),context=str(context),app=str(app)); control(config_path,'enable')
            scheduler.run(str(config_path),NOW)
            report=json.loads((root/'private/reports/latest.json').read_text())
            self.assertEqual(report['recoveryReminder']['state'],'dispatch_attempted')
            self.assertEqual(report['recoveryReminder']['delivery'],'unknown')
            self.assertEqual(control(config_path,'status')['permission'],'authorized')
            self.assertEqual(control(config_path,'stop')['removal'],'requested')
            scheduler.run(str(config_path),NOW+dt.timedelta(minutes=1))
            self.assertEqual(json.loads((root/'private/reports/latest.json').read_text())['recoveryReminder']['state'],'disabled')
            self.assertEqual(context.read_bytes(),before)
            self.assertFalse(json.loads(config_path.read_text())['recoveryReminder']['enabled'])
