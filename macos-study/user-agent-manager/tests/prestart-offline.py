#!/usr/bin/env python3
"""Read/write only the synthetic ledger produced by the scoped manager test."""
import datetime, importlib.util, json, os, pathlib, plistlib, subprocess, sys, time, uuid
ROOT=pathlib.Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('qa_scope',ROOT/'harness/qa_scope.py');scope=importlib.util.module_from_spec(spec);spec.loader.exec_module(scope)
log=pathlib.Path(os.environ.get('FOCUSDECK_QA_SCOPE_LOG',str(pathlib.Path.home()/'Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl')))
base=pathlib.Path(sys.argv[1]);assert str(base).startswith('/tmp/fd-manager-qa-') and (base/'ManagerCLI').is_file()
label='dev.stanislav.focusdeck.manager-qa-'+uuid.uuid4().hex;service=f'gui/{os.getuid()}/{label}';job=base/'jobs'/f'{label}.plist'
ledger=base/'ledger/protection-ledger.json';original=ledger.read_bytes(); sid=str(uuid.uuid4());env=dict(os.environ,FD_MANAGER_QA_ROOT=str(base),FD_MANAGER_QA_LABEL=label,FD_MANAGER_QA_APP=str(base/'v2/FocusDeck.app'))
def run(args,check=True):
 p=subprocess.run([str(a) for a in args],capture_output=True,text=True,env=env)
 if check and p.returncode:raise RuntimeError(f'{args[0]} failed')
 return p
def denied(data,reason):
 ledger.write_bytes(data); expected=ledger.read_bytes();p=run([base/'ManagerCLI','install'],False);v=json.loads(p.stdout)
 assert not v['ok'] and v['reason']==reason,(v,reason)
 assert ledger.read_bytes()==expected and not job.exists() and not (base/'agent.sock').exists()
 assert run(['/bin/launchctl','print',service],False).returncode!=0
status=0;clean=False
scope.append(log,'start',sid)
try:
 now=datetime.datetime.now(datetime.timezone.utc)
 def iso(t):return t.replace(microsecond=0).isoformat().replace('+00:00','Z')
 imminent=json.loads(original);imminent['policy']['state']='inactive';imminent['policy']['startsAt']=iso(now+datetime.timedelta(seconds=60));imminent['policy']['endsAt']=iso(now+datetime.timedelta(seconds=300))
 denied(json.dumps(imminent).encode(),'schedule_boundary')
 started=json.loads(original);started['policy']['state']='inactive';started['policy']['startsAt']=iso(now-datetime.timedelta(seconds=30));started['policy']['endsAt']=iso(now+datetime.timedelta(seconds=300))
 denied(json.dumps(started).encode(),'window_active')
 denied(b'{invalid synthetic ledger','agent_unknown')
 print('PASS: inactive imminent, inactive already started and malformed ledger refuse before launch and preserve bytes.')
except BaseException as e:status=1;print('QA failed:',e,file=sys.stderr)
finally:
 try:
  if run(['/bin/launchctl','print',service],False).returncode==0:
   if not job.exists():raise RuntimeError('Test job identity unavailable')
   args=plistlib.loads(job.read_bytes()).get('ProgramArguments',[])
   if len(args)!=1 or not pathlib.Path(args[0]).resolve().is_relative_to(base.resolve()):raise RuntimeError('Unrelated job')
   run(['/bin/launchctl','bootout',service])
  deadline=time.monotonic()+8
  while (base/'agent.sock').exists() and time.monotonic()<deadline:time.sleep(.1)
  if (base/'agent.sock').exists() or run(['/bin/launchctl','print',service],False).returncode==0:raise RuntimeError('Cleanup unknown')
  if job.exists():job.unlink()
  ledger.write_bytes(original);clean=True
 except BaseException as e:status=75;print('Scope remains open:',e,'scope',sid,'label',label,file=sys.stderr)
 if clean:scope.append(log,'end',sid)
sys.exit(status)
