#!/usr/bin/env python3
"""Scoped synthetic launchd integration. Uses only its unique QA label and data."""
import importlib.util, json, os, pathlib, subprocess, sys, tempfile, time, uuid
ROOT=pathlib.Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('qa_scope',ROOT/'harness/qa_scope.py');scope=importlib.util.module_from_spec(spec);spec.loader.exec_module(scope)
log=pathlib.Path(os.environ.get('FOCUSDECK_QA_SCOPE_LOG',str(pathlib.Path.home()/'Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl')))
sid=str(uuid.uuid4()); label='dev.stanislav.focusdeck.manager-qa-'+uuid.uuid4().hex
base=pathlib.Path(tempfile.mkdtemp(prefix='fd-manager-qa-',dir='/tmp')); service=f'gui/{os.getuid()}/{label}'
env=dict(os.environ,FD_MANAGER_QA_ROOT=str(base),FD_MANAGER_QA_LABEL=label)
common=['macos-study/Sources/WindowPolicy.swift','macos-study/Sources/WindowGuardV2.swift','macos-study/advanced-lab/Sources/StudyPolicy.swift','macos-study/advanced-lab/Sources/RootCommandCore.swift','macos-study/advanced-lab/Sources/RootGuardCore.swift','macos-study/advanced-lab/Sources/RootSocket.swift']
extra=['macos-study/advanced-lab/Sources/ProcessSignatureRequirement.swift','macos-study/advanced-lab/Sources/UserProtectionLedger.swift']
arch=subprocess.check_output(['/usr/bin/uname','-m'],text=True).strip()+'-apple-macos15.0'
def call(args,check=True,**kw):
 r=subprocess.run([str(a) for a in args],cwd=ROOT,capture_output=True,text=True,**kw)
 if check and r.returncode:raise RuntimeError(f'{args[0]} failed: {r.stderr[-1600:]} {r.stdout[-500:]}')
 return r
def compile_swift(sources,out,*flags):call(['/usr/bin/xcrun','swiftc','-target',arch,'-swift-version','6','-parse-as-library',*flags,*sources,'-framework','Security','-o',out])
def sign(path,ident,runtime=False):call(['/usr/bin/codesign','--force','--sign','-',*(['--options','runtime'] if runtime else []),'--identifier',ident,path]);call(['/usr/bin/codesign','--verify','--strict',path])
def cdhash(path):
 out=call(['/usr/bin/codesign','-dv','--verbose=4',path]).stderr
 return next(s[7:] for s in out.splitlines() if s.startswith('CDHash='))
def build_app(name, agent_source, runtime=False):
 app=base/name/'FocusDeck.app'; dest=app/'Contents/Library/HelperTools';dest.mkdir(parents=True)
 agent=dest/'UserProtectionAgent'; agent.write_bytes(pathlib.Path(agent_source).read_bytes());agent.chmod(0o700)
 sign(agent,'dev.stanislav.focusdeck.userprotection.agent',runtime)
 build=base/(name+'-host.swift');build.write_text('enum SiteHostBuild { static let agentHash = "'+cdhash(agent)+'" }\n')
 host=dest/'FocusDeckSiteHost';compile_swift(common+['macos-study/site-protection/NativeHost.swift',str(build)],host,'-D','SITE_HOST_TEST');sign(host,'dev.stanislav.focusdeck.sites.host')
 return app,agent
manager=base/'ManagerCLI';client=base/'Client'
def manage(app,action,ok=True,reason=None):
 r=call([manager,action],check=False,env=dict(env,FD_MANAGER_QA_APP=str(app))); value=json.loads(r.stdout)
 if value['ok']!=ok or (reason and value['reason']!=reason):raise AssertionError((action,value,r.stderr))
 return value
def agent_action(agent,action):call([client,base/'agent.sock',cdhash(agent),action])

def main():
 base.joinpath('jobs').mkdir();base.joinpath('chrome').mkdir()
 agent=base/'source-agent';compile_swift(common+extra+['macos-study/advanced-lab/Sources/UserProtectionAgent.swift'],agent,'-D','USER_GUARD_TEST')
 a,agent1=build_app('v1',agent); b,agent2=build_app('v2',agent,runtime=True)
 assert cdhash(agent1)!=cdhash(agent2)
 bad_source=base/'bad.swift';bad_source.write_text('import Foundation\n@main enum Bad { static func main() { exit(1) } }\n')
 bad_binary=base/'bad';compile_swift([str(bad_source)],bad_binary)
 bad,unused=build_app('vbad',bad_binary)
 compile_swift(common+extra+['macos-study/user-agent-manager/ScheduleBoundary.swift','macos-study/user-agent-manager/Manager.swift'],manager,'-D','USER_AGENT_MANAGER_TEST')
 sign(manager,'dev.stanislav.focusdeck.userprotection.manager')
 # If current local time is in the valid schedule range, issue today's
 # already-started recurring window. Its terminal exit must permit lifecycle.
 # At other times use tomorrow; deterministic boundary tests always run.
 boundary=base/'BoundaryTests'
 compile_swift(['macos-study/user-agent-manager/ScheduleBoundary.swift','macos-study/user-agent-manager/tests/ScheduleBoundaryTests.swift'],boundary)
 print(call([boundary]).stdout.strip())
 source=(ROOT/'macos-study/advanced-lab/site-window-qa/SiteAgentClient.swift').read_text()
 source=source.replace('case "relief": changed = current.relief(now: now)', 'case "rest": changed = current.rest(now: now)\n   case "relief": changed = current.relief(now: now)')
 source=source.replace('guard try exchange(WindowGuardRequest(policy: p, command: "publish", signingRequirements: requirements)).accepted else { throw RootSocketFailure.invalidResponse }', '''var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Europe/Minsk")!
    let minute = cal.component(.hour, from: now) * 60 + cal.component(.minute, from: now)
    let when = (minute >= 901 && minute < 1438) ? now : cal.date(byAdding: .day, value: 1, to: now)!
    let weekday = ((cal.component(.weekday, from: when) + 5) % 7) + 1
    let schedule = WindowSchedule(policyRevisionId: p.policyRevisionId, weekdays: [weekday], startMinute: 900, endMinute: 1439, targetRefs: p.targetRefs, reliefBudgetSeconds: p.reliefBudgetSeconds, reliefMaxGrantSeconds: p.reliefMaxGrantSeconds, exitCoolingOffSeconds: p.exitCoolingOffSeconds, siteDomains: p.siteDomains)
    let publishRequest = WindowGuardRequest(policy: p, command: "publish", signingRequirements: requirements, schedule: schedule)
    guard try exchange(publishRequest).accepted else { throw RootSocketFailure.invalidResponse }''')
 fixture=base/'Client.swift';fixture.write_text(source);compile_swift(common+[str(fixture)],client);sign(client,'dev.stanislav.focusdeck.manager.qa.client')
 assert manage(a,'status')['state']=='absent'
 fake=base/'fake-launchctl'; fake.write_text('#!/bin/sh\necho permission denied >&2\nexit 113\n');fake.chmod(0o700)
 for action in ('status','install'):
  r=call([manager,action],check=False,env=dict(env,FD_MANAGER_QA_APP=str(a),FD_MANAGER_QA_LAUNCHCTL=str(fake)))
  v=json.loads(r.stdout);assert not v['ok'] and v['reason']=='launchctl_unknown',(action,v)
 assert not any((base/'jobs').iterdir()) and not (base/'agent.sock').exists()
 manage(a,'install');assert manage(a,'status')['state']=='ready'
 agent_action(agent1,'publish');assert manage(a,'status')['state']=='active';manage(b,'update',False,'window_active')
 ledger=base/'ledger/protection-ledger.json'; snapshots={'active':ledger.read_bytes()}
 agent_action(agent1,'relief'); snapshots['relief']=ledger.read_bytes(); agent_action(agent1,'return')
 agent_action(agent1,'rest'); snapshots['rest']=ledger.read_bytes(); agent_action(agent1,'return')
 agent_action(agent1,'request_exit'); snapshots['exit-pending']=ledger.read_bytes();time.sleep(1.2);agent_action(agent1,'exit')
 before=ledger.read_bytes();assert json.loads(before)['schedule']['weekdays']; print('Synthetic schedule: started today' if json.loads(before)['schedule']['weekdays'][0] == __import__('datetime').datetime.now(__import__('zoneinfo').ZoneInfo('Europe/Minsk')).isoweekday() else 'Synthetic schedule: next day')
 chrome=base/'chrome/dev.stanislav.focusdeck.sites.json';chrome.write_text('{}')
 manage(b,'update',False,'chrome_host_registered');chrome.unlink()
 manage(b,'update');assert ledger.read_bytes()==before;assert manage(b,'status')['reason']=='schedule_enabled'
 before=ledger.read_bytes();manage(bad,'update',False,'update_rolled_back');assert ledger.read_bytes()==before
 assert manage(b,'status')['state']=='ready';manage(b,'uninstall');assert ledger.read_bytes()==before
 assert manage(b,'status')['state']=='absent'
 # A new manager process and a fresh install preserve terminal ledger/schedule.
 manage(b,'install');assert manage(b,'status')['state']=='ready';assert ledger.read_bytes()==before
 manage(b,'uninstall');assert ledger.read_bytes()==before
 def refused(data,reason):
  ledger.write_bytes(data); snapshot=ledger.read_bytes()
  manage(b,'install',False,reason)
  assert ledger.read_bytes()==snapshot and not (base/'agent.sock').exists() and not any((base/'jobs').iterdir()),reason
 for state,data in snapshots.items():refused(data,'window_active')
 unknown=json.loads(before);unknown['clockUncertain']=True
 refused(json.dumps(unknown).encode(),'clock_unknown')
 wrongboot=json.loads(before);wrongboot['clockAnchor']['boot']='synthetic-other-boot'
 refused(json.dumps(wrongboot).encode(),'clock_unknown')
 import datetime,zoneinfo
 now=datetime.datetime.now(zoneinfo.ZoneInfo('Europe/Minsk')); minute=now.hour*60+now.minute
 if 900 <= minute <= 1435:
  near=json.loads(before);near['schedule']['weekdays']=[now.isoweekday()];near['schedule']['startMinute']=minute+2;near['schedule']['endMinute']=minute+3
  refused(json.dumps(near).encode(),'schedule_boundary')
  due=json.loads(before);yesterday=(now-datetime.timedelta(days=1)).date().isoformat();due['lastIssuedLocalDate']=yesterday;due['policy']['localDate']=yesterday
  due['schedule']['weekdays']=[now.isoweekday()];due['schedule']['startMinute']=900;due['schedule']['endMinute']=1439
  refused(json.dumps(due).encode(),'schedule_would_start')
 else:print('Near/started schedule pre-start checks outside test clock range; omitted.')
 ledger.write_bytes(before)
 assert manage(b,'status')['state']=='absent';print('PASS: cold install, uninstall and restart/install preserve ledger/schedule; active, relief, rest, exit-pending, clock unknown, near boundary and started schedule deny before launch; existing lifecycle/rollback passed.')
 print('QA artifacts:',base)

scope.append(log,'start',sid);status=0;clean=False
try:main()
except BaseException as e:status=1;print('QA failed:',e,file=sys.stderr)
finally:
 try:
  r=call(['/bin/launchctl','print',service],check=False)
  if r.returncode==0:
   job=base/'jobs'/f'{label}.plist'
   if not job.exists():raise RuntimeError('Missing owned job; refusing bootout')
   import plistlib
   args=plistlib.loads(job.read_bytes()).get('ProgramArguments',[])
   if len(args)!=1 or not pathlib.Path(args[0]).resolve().is_relative_to(base.resolve()):raise RuntimeError('Job does not belong to test')
   call(['/bin/launchctl','bootout',service])
  deadline=time.monotonic()+8
  while (base/'agent.sock').exists() and time.monotonic()<deadline:time.sleep(.1)
  if call(['/bin/launchctl','print',service],check=False).returncode==0 or (base/'agent.sock').exists():raise RuntimeError('test job or socket remains')
  job=base/'jobs'/f'{label}.plist'
  if job.exists():job.unlink()
  clean=True
 except BaseException as e:status=75;print('Cleanup unknown; scope remains open:',e,'scope',sid,'label',label,file=sys.stderr)
 if clean:scope.append(log,'end',sid)
sys.exit(status)
