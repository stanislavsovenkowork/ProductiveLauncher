#!/usr/bin/env python3
"""Additional isolated conflict checks, using artifacts from a completed run.py."""
import importlib.util,json,os,pathlib,plistlib,shutil,socket,subprocess,sys,time,uuid
root=pathlib.Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('qa_scope',root/'harness/qa_scope.py'); scope=importlib.util.module_from_spec(spec);spec.loader.exec_module(scope)
log=pathlib.Path(os.environ.get('FOCUSDECK_QA_SCOPE_LOG',str(pathlib.Path.home()/'Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl')))
base=pathlib.Path(sys.argv[1]);assert str(base).startswith('/tmp/fd-manager-qa-') and base.is_dir()
ident=str(uuid.uuid4()); label='dev.stanislav.focusdeck.manager-qa-'+uuid.uuid4().hex
sm=label+'.sm'; service=f'gui/{os.getuid()}/{sm}'; job=base/'jobs'/f'{label}.plist'; smjob=base/'jobs'/f'{sm}.plist'
app=base/'v1/FocusDeck.app'; env=dict(os.environ,FD_MANAGER_QA_ROOT=str(base),FD_MANAGER_QA_LABEL=label,FD_MANAGER_QA_APP=str(app))
def run(args):return subprocess.run([str(a) for a in args],capture_output=True,text=True,env=env)
def call(action, reason):
 result=run([base/'ManagerCLI',action]);value=json.loads(result.stdout)
 assert not value['ok'] and value['reason']==reason,(action,value,result.stderr)
status=0; clean=False; sm_started=False; sock=None
scope.append(log,'start',ident)
try:
 job.write_text('not a FocusDeck job');before=job.read_bytes();call('install','service_conflict');assert job.read_bytes()==before;job.unlink()
 sock=socket.socket(socket.AF_UNIX);sock.bind(str(base/'agent.sock'));call('install','service_conflict');sock.close();sock=None;(base/'agent.sock').unlink()
 smjob.write_bytes(plistlib.dumps({'Label':sm,'ProgramArguments':['/bin/sleep','60'],'RunAtLoad':True}))
 r=run(['/bin/launchctl','bootstrap',f'gui/{os.getuid()}',smjob]);assert r.returncode==0,r.stderr;sm_started=True
 call('install','service_conflict')
 r=run(['/bin/launchctl','bootout',service]);assert r.returncode==0,r.stderr;sm_started=False;smjob.unlink()
 # Reuse only signed synthetic files, deliberately pair the wrong host pin.
 candidate=base/'vmismatch/FocusDeck.app/Contents/Library/HelperTools';candidate.mkdir(parents=True)
 shutil.copy2(base/'v1/FocusDeck.app/Contents/Library/HelperTools/UserProtectionAgent',candidate/'UserProtectionAgent')
 shutil.copy2(base/'v2/FocusDeck.app/Contents/Library/HelperTools/FocusDeckSiteHost',candidate/'FocusDeckSiteHost')
 env['FD_MANAGER_QA_APP']=str(base/'vmismatch/FocusDeck.app');call('install','chrome_pin_mismatch')
 print('PASS: existing foreign plist retained; occupied socket and loaded synthetic SM job reject install; mismatched signed Chrome host pin rejects install.')
except BaseException as e:status=1;print('QA failed:',e,file=sys.stderr)
finally:
 try:
  if sock:sock.close();(base/'agent.sock').unlink(missing_ok=True)
  if sm_started:
   r=run(['/bin/launchctl','bootout',service]);assert r.returncode==0,r.stderr
  if run(['/bin/launchctl','print',service]).returncode==0:raise RuntimeError('SM test service still loaded')
  if smjob.exists():smjob.unlink()
  if job.exists():
   if job.read_bytes()!=b'not a FocusDeck job':raise RuntimeError('job ownership unknown')
   job.unlink()
  clean=True
 except BaseException as e:status=75;print('Cleanup unknown, scope remains open:',e,'scope',ident,'label',label,file=sys.stderr)
 if clean:scope.append(log,'end',ident)
sys.exit(status)
