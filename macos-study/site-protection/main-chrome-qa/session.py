#!/usr/bin/env python3
"""Supervise a synthetic-only host and loopback page for a visible Chrome QA.

Called only after the operator verifies the new extension is OFF. The operator
must switch it OFF again before typing `off` on stdin; only then is the original
native-host manifest restored. No Chrome preferences or personal tabs are read.
"""
import argparse
import http.server
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import socketserver
import stat
import subprocess
import sys
import tempfile
import threading
import time
import uuid

ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('qa_scope',ROOT/'harness/qa_scope.py')
scope=importlib.util.module_from_spec(spec);spec.loader.exec_module(scope)
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('synthetic_directory')
parser.add_argument('--expected-production-host',required=True)
# Override exists solely for an isolated dry-run; never points into Chrome.
parser.add_argument('--isolated-manifest')
a=parser.parse_args()
directory=Path(a.synthetic_directory)
if not str(directory).startswith('/tmp/focusdeck-mainchrome-qa.') or not directory.is_dir():raise SystemExit('Invalid synthetic directory')
config=json.loads((directory/'config.json').read_text())
hostname=config['hostname']; socketpath=config['socket']; agenthash=config['agentCDHash']
if not re.fullmatch(r'focusdeckmainchromeqa[a-z0-9]+\.localhost',hostname) or socketpath!=str(directory/'agent.sock') or not re.fullmatch(r'[0-9a-f]{40}',agenthash):raise SystemExit('Invalid synthetic configuration')
manifest=(Path(a.isolated_manifest) if a.isolated_manifest else Path.home()/'Library/Application Support/Google/Chrome/NativeMessagingHosts/dev.stanislav.focusdeck.sites.json')
if a.isolated_manifest and not str(manifest).startswith(str(directory)+'/'):raise SystemExit('Invalid isolated manifest')
expected=Path(a.expected_production_host).resolve()
log=Path(os.environ.get('FOCUSDECK_QA_SCOPE_LOG',str(Path.home()/'Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl')))
id=str(uuid.uuid4()); agent=None; server=None; thread=None; old=None; oldmode=None; replacement=None; installed=False; exposed=False; cleaned=False

def read_owned():
 st=manifest.lstat()
 if not stat.S_ISREG(st.st_mode) or st.st_uid!=os.getuid() or st.st_size>65536:raise RuntimeError('Host manifest is not an owned regular file')
 fd=os.open(manifest,os.O_RDONLY|os.O_NOFOLLOW)
 try:
  with os.fdopen(fd,'rb') as f:return f.read(),stat.S_IMODE(st.st_mode)
 except:os.close(fd);raise

def replace_exact(expected_bytes,new_bytes,mode):
 now,_=read_owned()
 if now!=expected_bytes:raise RuntimeError('Host registration changed, refusing to replace it')
 fd,tmp=tempfile.mkstemp(prefix='.focusdeck-mainchrome-qa-',dir=manifest.parent)
 try:
  with os.fdopen(fd,'wb') as f:f.write(new_bytes);f.flush();os.fsync(f.fileno())
  os.chmod(tmp,mode);os.replace(tmp,manifest)
 finally:
  if os.path.exists(tmp):os.unlink(tmp)

def client(action):
 p=subprocess.run([str(directory/'Client'),socketpath,agenthash,action],capture_output=True,text=True,timeout=5)
 if p.returncode:raise RuntimeError('Synthetic agent rejected '+action)
 return json.loads(p.stdout)

class Page(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  if self.headers.get('Host')!=f'{hostname}:{server.server_address[1]}':self.send_error(404);return
  body=b'<!doctype html><meta charset="utf-8"><title>FocusDeck synthetic QA</title><main><h1>FocusDeck: test page</h1><p>This is a synthetic local page.</p><button>Test interaction</button></main>'
  self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(body)
 def log_message(self,*args):pass

scope.append(log,'start',id)
try:
 old,oldmode=read_owned(); oldobj=json.loads(old)
 if oldobj.get('name')!='dev.stanislav.focusdeck.sites' or Path(oldobj.get('path','')).resolve()!=expected or oldobj.get('allowed_origins')!=['chrome-extension://dcfilodddhkflkekabenoekoaeoblhao/']:
  raise RuntimeError('Existing host registration is not the expected FocusDeck build')
 backup=directory/'original-host-manifest.json'
 fd=os.open(backup,os.O_CREAT|os.O_EXCL|os.O_WRONLY|os.O_NOFOLLOW,0o600)
 with os.fdopen(fd,'wb') as f:f.write(old);f.flush();os.fsync(f.fileno())
 metadata={'scopeId':id,'manifest':str(manifest),'expectedProductionHost':str(expected),'originalSHA256':hashlib.sha256(old).hexdigest(),'originalMode':oldmode,'syntheticWrapper':str(directory/'host-wrapper')}
 (directory/'recovery-info.json').write_text(json.dumps(metadata,indent=2)+'\n')
 os.chmod(directory/'recovery-info.json',0o600)
 if (directory/'agent.sock').exists() or (directory/'data').exists():raise RuntimeError('Synthetic directory already used')
 child_env=dict(os.environ,FOCUSDECK_USER_TEST_DIR=str(directory/'data'),FOCUSDECK_USER_TEST_SOCKET=socketpath)
 agent=subprocess.Popen([str(directory/'Agent')],env=child_env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 deadline=time.monotonic()+10
 while not Path(socketpath).exists() and agent.poll() is None and time.monotonic()<deadline:time.sleep(.1)
 if not Path(socketpath).exists():raise RuntimeError('Synthetic agent did not start')
 client('publish_site')
 ledger=json.loads((directory/'data/protection-ledger.json').read_text())
 p=ledger['policy']
 if p.get('siteDomains')!=[hostname] or p.get('siteSubdomains') is not True or p.get('targetRefs')!=[] or ledger.get('signingRequirements')!=[] or p.get('state')!='active':raise RuntimeError('Synthetic-only policy not confirmed')
 test_request=json.dumps({'kind':'status'}).encode();frame=len(test_request).to_bytes(4,'little')+test_request
 probe=subprocess.run([str(directory/'host-wrapper')],input=frame,capture_output=True,timeout=5)
 if probe.returncode or len(probe.stdout)<4:raise RuntimeError('Synthetic host did not respond')
 n=int.from_bytes(probe.stdout[:4],'little');response=json.loads(probe.stdout[4:4+n])
 if response.get('phase')!='active' or response.get('domains')!=[hostname] or response.get('includeSubdomains') is not True:raise RuntimeError('Synthetic host did not confirm policy')
 server=socketserver.TCPServer(('127.0.0.1',0),Page);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
 replacement=json.dumps({'name':'dev.stanislav.focusdeck.sites','description':'FocusDeck synthetic QA','path':str(directory/'host-wrapper'),'type':'stdio','allowed_origins':['chrome-extension://dcfilodddhkflkekabenoekoaeoblhao/']},sort_keys=True).encode()
 replace_exact(old,replacement,0o600);installed=True
 print('READY: synthetic policy only: '+hostname,flush=True)
 print(f'Open BEFORE installing the extension: http://{hostname}:{server.server_address[1]}/',flush=True)
 print('After testing: enter exit here; then switch site action OFF in the extension, verify OFF and enter off here.',flush=True)
 exposed=True
 while True:
  command=sys.stdin.readline().strip()
  if command == 'exit':
   client('request_exit');time.sleep(1.2);client('exit')
   print('Synthetic window exited. Switch site action OFF, verify OFF, then enter off here.',flush=True)
  elif command == 'off':
   state=json.loads((directory/'data/protection-ledger.json').read_text())['policy']['state']
   if state not in ('exit','expired'):
    print('Synthetic window still open; enter exit first.',flush=True);continue
   break
  else:raise RuntimeError('OFF not confirmed by operator')
 server.shutdown();server.server_close();thread.join(timeout=5);server=None
 agent.terminate();agent.wait(timeout=8);agent=None
 if Path(socketpath).exists():raise RuntimeError('Synthetic agent socket remains')
 replace_exact(replacement,old,oldmode);installed=False
 scope.append(log,'end',id);cleaned=True
 print('DONE: production host registration restored, synthetic processes stopped, scope closed.',flush=True)
except BaseException as e:
 print('QA stopped:',e,file=sys.stderr,flush=True)
 if not exposed:
  try:
   if server:server.shutdown();server.server_close()
   if agent and agent.poll() is None:agent.terminate();agent.wait(timeout=8)
   if installed:replace_exact(replacement,old,oldmode);installed=False
   if not Path(socketpath).exists():scope.append(log,'end',id);cleaned=True
  except BaseException as cleanup_error:print('Cleanup not confirmed:',cleanup_error,file=sys.stderr,flush=True)
 else:
  # Never restore a personal policy while the extension may still be enabled.
  if agent and agent.poll() is None:
   try:agent.terminate();agent.wait(timeout=8)
   except Exception:pass
 if not cleaned:print('Scope remains open; synthetic host may still be registered. Scope:',id,'Recovery files:',directory,file=sys.stderr,flush=True)
 sys.exit(75 if not cleaned else 1)
