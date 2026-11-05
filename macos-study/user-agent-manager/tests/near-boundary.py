#!/usr/bin/env python3
"""Exercise actual lifecycle refusal near an upcoming synthetic schedule."""
import datetime, importlib.util, json, os, pathlib, plistlib, shutil, subprocess, sys, tempfile, time, uuid, zoneinfo
ROOT=pathlib.Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('qa_scope',ROOT/'harness/qa_scope.py');scope=importlib.util.module_from_spec(spec);spec.loader.exec_module(scope)
log=pathlib.Path(os.environ.get('FOCUSDECK_QA_SCOPE_LOG',str(pathlib.Path.home()/'Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl')))
source=pathlib.Path(sys.argv[1]); assert str(source).startswith('/tmp/fd-manager-qa-')
base=pathlib.Path(tempfile.mkdtemp(prefix='fd-manager-qa-',dir='/tmp')); label='dev.stanislav.focusdeck.manager-qa-'+uuid.uuid4().hex
service=f'gui/{os.getuid()}/{label}'; job=base/'jobs'/f'{label}.plist';sid=str(uuid.uuid4())
env=dict(os.environ,FD_MANAGER_QA_ROOT=str(base),FD_MANAGER_QA_LABEL=label)
def run(args,check=True,**kw):
 p=subprocess.run([str(a) for a in args],cwd=ROOT,capture_output=True,text=True,**kw)
 if check and p.returncode:raise RuntimeError(f'{args[0]} failed: {p.stderr[-1000:]} {p.stdout[-500:]}')
 return p
def manage(app,command,reason=None):
 p=run([source/'ManagerCLI',command],check=False,env=dict(env,FD_MANAGER_QA_APP=str(app))); result=json.loads(p.stdout)
 if reason:assert not result['ok'] and result['reason']==reason,(command,result)
 else:assert result['ok'],(command,result)
 return result
status=0;clean=False
scope.append(log,'start',sid)
try:
 now=datetime.datetime.now(zoneinfo.ZoneInfo('Europe/Minsk'));minute=now.hour*60+now.minute
 if not 900 <= minute <= 1435:raise RuntimeError('Local time outside safe synthetic near-start test range')
 (base/'jobs').mkdir();shutil.copytree(source/'v1',base/'v1');shutil.copytree(source/'v2',base/'v2')
 app=base/'v1/FocusDeck.app';new_app=base/'v2/FocusDeck.app';agent=app/'Contents/Library/HelperTools/UserProtectionAgent'
 sha=run(['/usr/bin/codesign','-dv','--verbose=4',agent]).stderr;hash=next(x[7:] for x in sha.splitlines() if x.startswith('CDHash='))
 raw=(ROOT/'macos-study/advanced-lab/site-window-qa/SiteAgentClient.swift').read_text()
 old='guard try exchange(WindowGuardRequest(policy: p, command: "publish", signingRequirements: requirements)).accepted else { throw RootSocketFailure.invalidResponse }'
 new='''var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Europe/Minsk")!
    let parts = cal.dateComponents([.year,.month,.day,.hour,.minute], from: now)
    p.localDate = String(format: "%04d-%02d-%02d", parts.year!,parts.month!,parts.day!)
    let minute = parts.hour! * 60 + parts.minute!, weekday = ((cal.component(.weekday, from: now) + 5) % 7) + 1
    let schedule = WindowSchedule(policyRevisionId:p.policyRevisionId, weekdays:[weekday], startMinute:minute+2, endMinute:minute+3, targetRefs:p.targetRefs, reliefBudgetSeconds:p.reliefBudgetSeconds, reliefMaxGrantSeconds:p.reliefMaxGrantSeconds, exitCoolingOffSeconds:p.exitCoolingOffSeconds, siteDomains:p.siteDomains)
    let publishRequest = WindowGuardRequest(policy:p, command:"publish", signingRequirements:requirements, schedule:schedule)
    guard try exchange(publishRequest).accepted else { throw RootSocketFailure.invalidResponse }'''
 assert raw.count(old)==1
 swift=base/'Client.swift';swift.write_text(raw.replace(old,new))
 common=['macos-study/Sources/WindowPolicy.swift','macos-study/Sources/WindowGuardV2.swift','macos-study/advanced-lab/Sources/StudyPolicy.swift','macos-study/advanced-lab/Sources/RootCommandCore.swift','macos-study/advanced-lab/Sources/RootGuardCore.swift','macos-study/advanced-lab/Sources/RootSocket.swift']
 client=base/'Client';arch=run(['/usr/bin/uname','-m']).stdout.strip()+'-apple-macos15.0'
 run(['/usr/bin/xcrun','swiftc','-target',arch,'-swift-version','6','-parse-as-library',*common,swift,'-framework','Security','-o',client]);run(['/usr/bin/codesign','--force','--sign','-','--identifier','dev.stanislav.focusdeck.near.qa.client',client])
 def action(value):run([client,base/'agent.sock',hash,value])
 manage(app,'install');action('publish');action('request_exit');time.sleep(1.2);action('exit')
 ledger=base/'ledger/protection-ledger.json';before=ledger.read_bytes()
 saved=json.loads(before); assert saved['policy']['state']=='exit'
 remain=(datetime.datetime.combine(now.date(),datetime.time.min,tzinfo=now.tzinfo)+datetime.timedelta(minutes=minute+2)-datetime.datetime.now(now.tzinfo)).total_seconds()
 assert 0 < remain <= 120,remain
 manage(new_app,'update','schedule_boundary');manage(app,'uninstall','schedule_boundary')
 assert ledger.read_bytes()==before
 assert run(['/bin/launchctl','print',service],check=False).returncode==0
 print('PASS: terminal synthetic exit with upcoming start within 120 seconds refuses update and uninstall, keeps service and ledger intact.')
 print('QA artifacts:',base)
except BaseException as e:status=1;print('QA failed:',e,file=sys.stderr)
finally:
 try:
  if run(['/bin/launchctl','print',service],check=False).returncode==0:
   if not job.exists():raise RuntimeError('Cannot prove test job ownership')
   args=plistlib.loads(job.read_bytes()).get('ProgramArguments',[])
   if len(args)!=1 or not pathlib.Path(args[0]).resolve().is_relative_to(base.resolve()):raise RuntimeError('Unrelated job')
   run(['/bin/launchctl','bootout',service])
  deadline=time.monotonic()+8
  while (base/'agent.sock').exists() and time.monotonic()<deadline:time.sleep(.1)
  if (base/'agent.sock').exists() or run(['/bin/launchctl','print',service],check=False).returncode==0:raise RuntimeError('Cleanup not confirmed')
  if job.exists():job.unlink()
  clean=True
 except BaseException as e:status=75;print('QA scope remains open:',e,'scope',sid,'label',label,file=sys.stderr)
 if clean:scope.append(log,'end',sid)
sys.exit(status)
