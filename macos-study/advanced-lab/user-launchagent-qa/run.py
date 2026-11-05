#!/usr/bin/env python3
"""Isolated user LaunchAgent lifecycle QA. Never touches the real FocusDeck jobs."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('qa_scope', ROOT/'harness/qa_scope.py')
SCOPE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCOPE)
LOG = Path(os.environ.get('FOCUSDECK_QA_SCOPE_LOG', str(Path.home()/'Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl')))
IDENT = str(uuid.uuid4())
LABEL = 'dev.stanislav.focusdeck.synthetic-lifecycle-' + uuid.uuid4().hex
JOB = Path.home()/'Library/LaunchAgents'/f'{LABEL}.plist'
DOMAIN = f'gui/{os.getuid()}'
SERVICE = f'{DOMAIN}/{LABEL}'
DIR = Path(tempfile.mkdtemp(prefix='fd-launchagent-qa-', dir='/tmp')).resolve()
SOCKET = DIR/'agent.sock'
DATA = DIR/'ledger'
CLIENT = DIR/'Client'
installed_bytes = None
launched = False


def command(args, *, check=True, **kwargs):
    result = subprocess.run([str(a) for a in args], capture_output=True, text=True, **kwargs)
    if check and result.returncode:
        raise RuntimeError(f'{args[0]} returned {result.returncode}: {result.stderr[-1200:]}')
    return result


def wait(predicate, name, timeout=15):
    deadline = time.monotonic()+timeout
    while time.monotonic()<deadline:
        if predicate(): return
        time.sleep(.1)
    raise RuntimeError('Timeout: '+name)


def loaded():
    return command(['/bin/launchctl','print',SERVICE],check=False).returncode==0


def bootout():
    global launched
    if loaded(): command(['/bin/launchctl','bootout',SERVICE])
    wait(lambda:not loaded() and not SOCKET.exists(), 'service and socket stopped')
    launched=False


def bootstrap():
    global launched
    command(['/bin/launchctl','bootstrap',DOMAIN,JOB])
    launched=True
    wait(lambda:SOCKET.exists(), 'agent socket')


def hash_code(binary):
    result=command(['/usr/bin/codesign','-dv','--verbose=4',binary])
    for line in result.stderr.splitlines():
        if line.startswith('CDHash='): return line[7:].lower()
    raise RuntimeError('Missing CDHash')


def ask(binary, action):
    result=command([CLIENT,SOCKET,hash_code(binary),action])
    return json.loads(result.stdout)


def make_plist(binary):
    return plistlib.dumps({'Label':LABEL, 'ProgramArguments':[str(binary)], 'RunAtLoad':True,
        'KeepAlive':{'SuccessfulExit':False}, 'ThrottleInterval':30, 'Umask':63,
        'EnvironmentVariables':{'FOCUSDECK_USER_TEST_SOCKET':str(SOCKET),'FOCUSDECK_USER_TEST_DIR':str(DATA)},
        'StandardOutPath':str(DIR/'stdout.log'), 'StandardErrorPath':str(DIR/'stderr.log')})


def write_job(content):
    global installed_bytes
    # An occupied path or job is never replaced. Updates require exact bytes we wrote.
    if installed_bytes is None:
        fd=os.open(JOB,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'wb') as f: f.write(content)
    else:
        if not JOB.exists() or JOB.read_bytes()!=installed_bytes: raise RuntimeError('Job changed; refusing replacement')
        fd, tmp=tempfile.mkstemp(dir=JOB.parent,prefix='.fd-synthetic-')
        try:
            with os.fdopen(fd,'wb') as f: f.write(content)
            os.chmod(tmp,0o600);os.replace(tmp,JOB)
        finally:
            if os.path.exists(tmp):os.unlink(tmp)
    installed_bytes=content


def build():
    common=['macos-study/Sources/WindowPolicy.swift','macos-study/Sources/WindowGuardV2.swift',
      'macos-study/advanced-lab/Sources/StudyPolicy.swift','macos-study/advanced-lab/Sources/RootCommandCore.swift',
      'macos-study/advanced-lab/Sources/RootGuardCore.swift','macos-study/advanced-lab/Sources/RootSocket.swift']
    arch=command(['/usr/bin/uname','-m']).stdout.strip()+'-apple-macos15.0'
    sources=common+['macos-study/advanced-lab/Sources/ProcessSignatureRequirement.swift',
      'macos-study/advanced-lab/Sources/UserProtectionLedger.swift','macos-study/advanced-lab/Sources/UserProtectionAgent.swift']
    a=DIR/'Agent1'; b=DIR/'Agent2'
    command(['/usr/bin/xcrun','swiftc','-target',arch,'-swift-version','6','-D','USER_GUARD_TEST','-parse-as-library',*sources,'-framework','Security','-o',a],cwd=ROOT)
    b.write_bytes(a.read_bytes());b.chmod(0o700)
    for binary, identifier in ((a,'dev.stanislav.focusdeck.lifecycle.qa.one'),(b,'dev.stanislav.focusdeck.lifecycle.qa.two')):
        command(['/usr/bin/codesign','--force','--sign','-','--identifier',identifier,binary])
        command(['/usr/bin/codesign','--verify','--strict',binary])
    client_source=(ROOT/'macos-study/advanced-lab/site-window-qa/SiteAgentClient.swift').read_text()
    client_source=client_source.replace('p.reliefMaxGrantSeconds = 5;', 'p.reliefMaxGrantSeconds = 90;')
    (DIR/'Client.swift').write_text(client_source)
    command(['/usr/bin/xcrun','swiftc','-target',arch,'-swift-version','6','-parse-as-library',*common,DIR/'Client.swift','-framework','Security','-o',CLIENT],cwd=ROOT)
    command(['/usr/bin/codesign','--force','--sign','-','--identifier','dev.stanislav.focusdeck.lifecycle.qa.client',CLIENT])
    return a,b


def main():
    if JOB.exists() or loaded(): raise RuntimeError('Unexpected label collision')
    a,b=build()
    if hash_code(a)==hash_code(b): raise RuntimeError('Update did not change agent CDHash')
    JOB.parent.mkdir(parents=True,exist_ok=True)
    write_job(make_plist(a)); bootstrap()
    assert ask(a,'status')['phase']=='released'
    ask(a,'publish'); assert ask(a,'status')['phase']=='active'
    ask(a,'relief'); assert ask(a,'status')['phase']=='released'
    ledger=DATA/'protection-ledger.json'
    before=ledger.read_bytes(); before_state=json.loads(before)
    assert before_state['policy']['state']=='limited-relief' and before_state['policy']['reliefRemainingSeconds']==30
    bootout(); write_job(make_plist(b)); bootstrap()
    assert ask(b,'status')['phase']=='released'
    after=ledger.read_bytes()
    assert before==after and json.loads(after)['policy']['reliefRemainingSeconds']==30
    ask(b,'return'); assert ask(b,'status')['phase']=='active'
    ledger_after_return=ledger.read_bytes()
    bootout()
    if not JOB.exists() or JOB.read_bytes()!=installed_bytes: raise RuntimeError('Job changed; refusing removal')
    JOB.unlink()
    assert ledger.read_bytes()==ledger_after_return
    print('PASS: synthetic user LaunchAgent installed, updated to distinct CDHash, restarted with byte-identical ledger and unspent budget, returned, stopped and uninstalled; ledger retained.')
    print('Synthetic artifacts: '+str(DIR))
    print('Agent1 SHA256:',hashlib.sha256(a.read_bytes()).hexdigest(),'CDHash:',hash_code(a))
    print('Agent2 SHA256:',hashlib.sha256(b.read_bytes()).hexdigest(),'CDHash:',hash_code(b))


if __name__=='__main__':
    SCOPE.append(LOG,'start',IDENT)
    status=0; clean=False
    try: main()
    except BaseException as e:
        status=1; print('QA failed:',e,file=sys.stderr)
    finally:
        try:
            if launched: bootout()
            if installed_bytes is not None and JOB.exists():
                if JOB.read_bytes()!=installed_bytes: raise RuntimeError('Job changed; refusing cleanup')
                JOB.unlink()
            if loaded() or SOCKET.exists() or JOB.exists(): raise RuntimeError('Job or socket remains')
            clean=True
        except BaseException as e:
            status=75; print('QA cleanup not confirmed; scope remains open:',e,file=sys.stderr)
            print('Own label:',LABEL,'scope:',IDENT,file=sys.stderr)
        if clean: SCOPE.append(LOG,'end',IDENT)
    sys.exit(status)
