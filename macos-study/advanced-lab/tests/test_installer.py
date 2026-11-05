"""Virtual-root installer fault tests. No actual root paths, launchctl or admin executed."""
from pathlib import Path
import json
import os
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'install-root-guard.sh'

class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='focusdeck-installer-test-')
        self.base = Path(self.tmp.name)
        self.stage = self.base/'stage'; self.stage.mkdir()
        self.root = self.base/'virtual-root'; self.root.mkdir()
        for path in ['build', 'Packaging', 'config']: (self.stage/path).mkdir()
        (self.stage/'build/FocusDeckRootGuard').write_text('#!/bin/sh\nexit 0\n')
        (self.stage/'build/FocusDeckGuardAdmin').write_text('#!/bin/sh\nprintf recovered >> "$ADMIN_EXECUTION_LOG"\n')
        for file in (self.stage/'build').iterdir(): file.chmod(0o755)
        shutil.copy(SOURCE.parent/'Packaging/dev.stanislav.focusdeck.guard.plist', self.stage/'Packaging')
        policy = dict(blocked_app_bundle_ids=['dev.fixture'], blocked_domains=['fixture.test'], protected_domains=['openai.com'])
        (self.stage/'config/study.json').write_text(json.dumps(policy))
        script = SOURCE.read_text().replace('/Library/', str(self.root/'Library')+'/').replace('/private/etc/hosts',str(self.root/'private/etc/hosts'))
        hosts=self.root/'private/etc/hosts'; hosts.parent.mkdir(parents=True); hosts.write_text('127.0.0.1 localhost\n')
        # Plist ProgramArguments must point to the same virtual namespace.
        plist = self.stage/'Packaging/dev.stanislav.focusdeck.guard.plist'
        plist.write_text(plist.read_text().replace('/Library/', str(self.root/'Library')+'/'))
        self.script = self.stage/'install.sh'; self.script.write_text(script)
        self.ui = self.base/'fake-ui'; self.ui.write_text('fixture')
        self.bin = self.base/'bin'; self.bin.mkdir()
        self.env = dict(os.environ, PATH=str(self.bin)+':'+os.environ['PATH'], STATE=str(self.base/'loaded'), ADMIN_EXECUTION_LOG=str(self.base/'admin-log'))
        self.stub('id', 'import sys\nprint("0" if sys.argv[1]=="-u" else "fixture")')
        self.stub('stat', 'import subprocess,sys\nif sys.argv[2]=="%u": print(0)\nelse: raise SystemExit(subprocess.call(["/usr/bin/stat"]+sys.argv[1:]))')
        self.stub('codesign', 'import sys\nif "-dv" in sys.argv: print("CDHash="+"a"*40,file=sys.stderr)')
        self.stub('install', '''import sys,shutil,os
args=sys.argv[1:]; directory=False; mode=0o755; paths=[]
i=0
while i<len(args):
 a=args[i]
 if a=='-d': directory=True; i+=1
 elif a in ['-o','-g']: i+=2
 elif a=='-m': mode=int(args[i+1],8); i+=2
 else: paths.append(a); i+=1
if directory:
 for p in paths: os.makedirs(p,exist_ok=True); os.chmod(p,mode)
else: shutil.copyfile(paths[0],paths[1]); os.chmod(paths[1],mode)
''')
        self.stub('launchctl', '''import sys,os,pathlib
p=pathlib.Path(os.environ['STATE']); action=sys.argv[1]
if action=='print': raise SystemExit(0 if p.exists() else 1)
if action=='bootstrap':
 p.write_text('1')
 if os.environ.get('FAIL_BOOTSTRAP')=='1': raise SystemExit(55)
elif action=='bootout': p.unlink(missing_ok=True)
''')
    def stub(self,name,body):
        p=self.bin/name; p.write_text('#!/usr/bin/env python3\n'+body+'\n'); p.chmod(0o755)
    def tearDown(self): self.tmp.cleanup()
    def run_install(self, **env):
        return subprocess.run(['/bin/sh',str(self.script),'--install-idle','501',str(self.ui),'a'*40],env=dict(self.env,**env),capture_output=True,text=True)
    def remove(self):
        return subprocess.run(['/bin/sh',str(self.script),'--remove'],env=self.env,capture_output=True,text=True)
    @property
    def config(self): return self.root/'Library/Application Support/FocusDeckGuard'
    @property
    def helper(self): return self.root/'Library/PrivilegedHelperTools/dev.stanislav.focusdeck.guard'
    def test_install_idle_then_unloaded_remove(self):
        self.assertEqual(self.run_install().returncode,0)
        self.assertFalse((self.config/'session.json').exists())
        (self.base/'loaded').unlink()
        self.assertEqual(self.remove().returncode,0)
        self.assertFalse(self.helper.exists())
        self.assertEqual((self.base/'admin-log').read_text(),'recovered')
    def test_bootstrap_failure_rolls_back(self):
        self.assertNotEqual(self.run_install(FAIL_BOOTSTRAP='1').returncode,0)
        self.assertFalse(self.helper.exists()); self.assertFalse((self.config/'root-config.json').exists())
        self.assertFalse((self.base/'loaded').exists())
        self.assertEqual(self.run_install().returncode,0)
    def test_foreign_replaced_admin_is_not_executed_or_removed(self):
        self.assertEqual(self.run_install().returncode,0)
        admin=self.helper.with_name(self.helper.name+'.admin'); admin.write_text('foreign fixture')
        self.assertNotEqual(self.remove().returncode,0)
        self.assertTrue(admin.exists()); self.assertFalse((self.base/'admin-log').exists())
    def test_symlink_directory_preserved_without_install(self):
        self.config.parent.mkdir(parents=True)
        foreign=self.base/'foreign'; foreign.mkdir(); marker=foreign/'marker'; marker.write_text('untouched')
        self.config.symlink_to(foreign,target_is_directory=True)
        self.assertNotEqual(self.run_install().returncode,0)
        self.assertEqual(marker.read_text(),'untouched'); self.assertFalse(self.helper.exists())
    def test_existing_policy_refuses_idle_install(self):
        self.config.mkdir(parents=True,mode=0o700); (self.config/'session.json').write_text('foreign existing policy')
        self.assertNotEqual(self.run_install().returncode,0)
        self.assertFalse(self.helper.exists())

if __name__=='__main__': unittest.main()
