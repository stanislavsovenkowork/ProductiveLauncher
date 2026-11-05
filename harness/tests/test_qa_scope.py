import datetime as dt
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
import uuid
from harness import qa_scope, metrics
UTC=dt.timezone.utc
T=dt.datetime(2026,10,29,9,tzinfo=UTC)

def row(a,b):
    return {'occurredAt':b.isoformat(),'startedAt':a.isoformat(),'endedAt':b.isoformat(),'source':'mac','category':None,'context':{'bundleId':'example.app','observedSeconds':(b-a).total_seconds()}}

class QAScopeTests(unittest.TestCase):
    def test_overlap_close_and_crash_unknown(self):
        with tempfile.TemporaryDirectory() as d:
            path=pathlib.Path(d)/'qa.jsonl'; ident=str(uuid.uuid4())
            qa_scope.append(path,'start',ident,T)
            rows=[row(T-dt.timedelta(seconds=1),T+dt.timedelta(seconds=1)),row(T+dt.timedelta(seconds=3),T+dt.timedelta(seconds=5))]
            got,health=metrics.separate_qa(rows,path,T+dt.timedelta(seconds=6))
            self.assertEqual(got,[]);self.assertEqual(health['state'],'incomplete_open_scope')
            qa_scope.append(path,'end',ident,T+dt.timedelta(seconds=2))
            got,health=metrics.separate_qa(rows,path,T+dt.timedelta(seconds=6))
            self.assertEqual(got,[rows[1]]);self.assertEqual(health['state'],'observed')
            self.assertEqual(health['excludedEvents'],1)
    def test_init_preserves_existing_metadata_and_sets_boundary(self):
        script=pathlib.Path(__file__).resolve().parents[1]/'qa_scope.py'
        with tempfile.TemporaryDirectory() as d:
            path=pathlib.Path(d)/'scope.jsonl'
            subprocess.run([sys.executable,str(script),'init','--log',str(path)],check=True)
            before=path.read_bytes()
            self.assertNotEqual(subprocess.run([sys.executable,str(script),'init','--log',str(path)],capture_output=True).returncode,0)
            self.assertEqual(path.read_bytes(),before)
            _,health=metrics.separate_qa([],path,dt.datetime.now(UTC)+dt.timedelta(seconds=1))
            self.assertEqual(health['state'],'observed')
            self.assertEqual(health['openScopes'],0)
    def test_unconfirmed_child_exit_keeps_scope_open(self):
        script=pathlib.Path(__file__).resolve().parents[1]/'qa_scope.py'
        with tempfile.TemporaryDirectory() as d:
            path=pathlib.Path(d)/'scope.jsonl'
            result=subprocess.run([sys.executable,str(script),'run','--log',str(path),'--',sys.executable,'-c','raise SystemExit(75)'])
            self.assertEqual(result.returncode,75)
            _,health=metrics.separate_qa([],path,dt.datetime.now(UTC)+dt.timedelta(seconds=1))
            self.assertEqual(health['state'],'incomplete_open_scope')

    def test_missing_metadata_is_unknown_and_omits_context(self):
        with tempfile.TemporaryDirectory() as d:
            rows=[row(T,T+dt.timedelta(seconds=2))]
            got,health=metrics.separate_qa(rows,pathlib.Path(d)/'missing',T+dt.timedelta(seconds=3))
            self.assertEqual(got,[]);self.assertEqual(health['state'],'metadata_unknown')
    def test_killed_wrapper_leaves_visible_open_scope(self):
        import time
        script=pathlib.Path(__file__).resolve().parents[1]/'qa_scope.py'
        with tempfile.TemporaryDirectory() as d:
            path=pathlib.Path(d)/'scopes.jsonl'
            proc=subprocess.Popen([sys.executable,str(script),'run','--log',str(path),'--',sys.executable,'-c','import time; time.sleep(0.5)'])
            try:
                for _ in range(100):
                    if path.exists() and path.stat().st_size: break
                    time.sleep(.01)
                self.assertTrue(path.exists())
                proc.kill(); proc.wait(timeout=2)
                now=dt.datetime.now(UTC)+dt.timedelta(seconds=1)
                _,health=metrics.separate_qa([],path,now)
                self.assertEqual(health['state'],'incomplete_open_scope')
                self.assertEqual(health['openScopes'],1)
            finally:
                if proc.poll() is None: proc.kill(); proc.wait()

    def test_manual_multistep_scope_waits_for_explicit_confirmation(self):
        script=pathlib.Path(__file__).resolve().parents[1]/'qa_scope.py'
        command='printf "%s\\n" "QA scope open"; IFS= read -r answer && [ "$answer" = done ] || exit 75'
        with tempfile.TemporaryDirectory() as d:
            path=pathlib.Path(d)/'scope.jsonl'
            proc=subprocess.Popen([sys.executable,str(script),'run','--log',str(path),'--','/bin/sh','-c',command],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
            self.assertIn('QA scope open',proc.stdout.readline())
            now=dt.datetime.now(UTC)+dt.timedelta(seconds=1)
            self.assertEqual(metrics.separate_qa([],path,now)[1]['state'],'incomplete_open_scope')
            proc.stdin.write('done\n'); proc.stdin.flush(); self.assertEqual(proc.wait(timeout=5),0)
            self.assertEqual(metrics.separate_qa([],path,dt.datetime.now(UTC)+dt.timedelta(seconds=1))[1]['state'],'observed')
            proc.stdin.close(); proc.stdout.close()
            bad=pathlib.Path(d)/'bad.jsonl'
            result=subprocess.run([sys.executable,str(script),'run','--log',str(bad),'--','/bin/sh','-c',command],input='no\n',text=True,capture_output=True)
            self.assertEqual(result.returncode,75)
            self.assertEqual(metrics.separate_qa([],bad,dt.datetime.now(UTC)+dt.timedelta(seconds=1))[1]['state'],'incomplete_open_scope')

    def test_real_wrapper_closes_scope_on_setup_failure(self):
        import os, shutil
        root=pathlib.Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as d:
            base=pathlib.Path(d);(base/'macos-study').mkdir();(base/'harness').mkdir()
            shutil.copy(root/'macos-study/run-v2-ui-fixture.sh',base/'macos-study/run-v2-ui-fixture.sh')
            shutil.copy(root/'harness/qa_scope.py',base/'harness/qa_scope.py')
            log=base/'scopes.jsonl'; env=dict(os.environ,FOCUSDECK_QA_SCOPE_LOG=str(log))
            result=subprocess.run(['/bin/sh',str(base/'macos-study/run-v2-ui-fixture.sh')],env=env,capture_output=True)
            self.assertNotEqual(result.returncode,0)
            rows=[json.loads(x) for x in log.read_text().splitlines()]
            self.assertEqual([x['event'] for x in rows],['start','end'])
            self.assertEqual(rows[0]['scopeId'],rows[1]['scopeId'])
