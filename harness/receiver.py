#!/usr/bin/env python3
"""Authenticated loopback-only event ingestion; no request/content logging."""
import argparse
import contextlib
import datetime as dt
import fcntl
import hashlib
import hmac
import http.server
import importlib.util
import json
import os
import pathlib
import plistlib
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import time

HOST = '127.0.0.1'
PORT = 48731
MAX_BODY = 128 * 1024
MAX_EVENTS = 256
MAX_STORE = 32 * 1024 * 1024
LABEL = 'dev.focusdeck.local-ingestion'
SOURCES = ('mac','android','movebreak')
SCRIPT = pathlib.Path(__file__).resolve()


def load_validator():
    candidates = [SCRIPT.parent.parent/'shared/validate_events.py', SCRIPT.parent/'shared/validate_events.py']
    path = next((p for p in candidates if p.is_file()), None)
    if path is None:
        raise ValueError('shared validator unavailable')
    spec = importlib.util.spec_from_file_location('receiver_event_validator', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


VALIDATOR = load_validator()


def absolute(value):
    path = pathlib.Path(value)
    if not path.is_absolute():
        raise ValueError('absolute paths required')
    return path.resolve()


def private_write(path, content):
    path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    fd, temporary = tempfile.mkstemp(dir=path.parent,prefix='.ingestion-')
    try:
        with os.fdopen(fd,'wb') as stream:
            stream.write(content)
        os.replace(temporary,path)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


class EventStore:
    def __init__(self, directory, retention_days=90):
        self.directory = absolute(directory)
        self.directory.mkdir(parents=True,exist_ok=True,mode=0o700)
        os.chmod(self.directory,0o700)
        if type(retention_days) is not int or not 1 <= retention_days <= 3650:
            raise ValueError('retentionDays must be 1..3650')
        self.retention_days = retention_days
        self.lock = threading.Lock()

    @contextlib.contextmanager
    def locked(self):
        with self.lock:
            fd = os.open(self.directory/'.ingestion.lock',os.O_CREAT|os.O_RDWR,0o600)
            try:
                fcntl.flock(fd,fcntl.LOCK_EX)
                yield
            finally:
                os.close(fd)

    def read(self):
        records = {}
        for source in SOURCES:
            path = self.directory/f'{source}-events.jsonl'
            rows = []
            if path.exists():
                if path.stat().st_size > MAX_STORE:
                    raise ValueError('storage limit')
                for line in path.read_text().splitlines():
                    if line.strip():
                        row = VALIDATOR.validate(json.loads(line))
                        if row['source'] != source:
                            raise ValueError('source mismatch in store')
                        rows.append(row)
            records[source] = rows
        return records

    def receipt_metadata(self, records, now):
        path = self.directory/'.receipt-metadata.json'
        if path.exists():
            if path.stat().st_size>MAX_STORE: raise ValueError('receipt metadata limit')
            metadata = json.loads(path.read_text())
            if not isinstance(metadata,dict) or not isinstance(metadata.get('firstSeen'),dict) or not isinstance(metadata.get('sources'),dict):
                raise ValueError('invalid receipt metadata')
        else:
            metadata = {'firstSeen':{},'sources':{}}
        for row in [r for rows in records.values() for r in rows]:
            stamp = metadata['firstSeen'].get(row['eventId'])
            # Legacy records get a bounded migration receipt; host rollback cannot push it forever forward.
            first = min(VALIDATOR.timestamp(stamp),now) if stamp else min(VALIDATOR.timestamp(row['occurredAt']),now)
            metadata['firstSeen'][row['eventId']] = first.isoformat()
        return metadata

    def save_receipts(self, metadata, records):
        retained = {row['eventId'] for rows in records.values() for row in rows}
        metadata['firstSeen'] = {key:value for key,value in metadata['firstSeen'].items() if key in retained}
        private_write(self.directory/'.receipt-metadata.json',(json.dumps(metadata,separators=(',',':'))+'\n').encode())

    def ingest(self, events):
        if not isinstance(events,list) or not events or len(events) > MAX_EVENTS:
            raise ValueError('event batch size')
        for row in events:
            VALIDATOR.validate(row)
        sources = {row['source'] for row in events}
        if len(sources) != 1:
            raise ValueError('one source required per batch')
        with self.locked():
            records = self.read()
            now = dt.datetime.now(dt.timezone.utc)
            receipts = self.receipt_metadata(records,now)
            known = {}
            for row in [r for rows in records.values() for r in rows]:
                if row['eventId'] in known and known[row['eventId']] != row:
                    raise ValueError('stored conflicting event ids')
                known[row['eventId']] = row
            accepted, duplicates = [], 0
            for row in events:
                if row['eventId'] in known:
                    if known[row['eventId']] != row:
                        raise ConflictError()
                    duplicates += 1
                else:
                    known[row['eventId']] = row
                    accepted.append(row)
            source = next(iter(sources))
            cutoff = now-dt.timedelta(days=self.retention_days)
            # Historical uploads beyond retention are explicitly refused, not silently dropped.
            if any(VALIDATOR.timestamp(row['occurredAt']) < cutoff for row in accepted):
                raise ValueError('event older than retention window')
            for row in accepted: receipts['firstSeen'].setdefault(row['eventId'],now.isoformat())
            rows = [r for r in records[source] if VALIDATOR.timestamp(r['occurredAt']) >= cutoff and VALIDATOR.timestamp(receipts['firstSeen'][r['eventId']])>=cutoff]+accepted
            data = ''.join(json.dumps(r,separators=(',',':'),ensure_ascii=False)+'\n' for r in rows).encode()
            if len(data) > MAX_STORE:
                raise ValueError('storage limit')
            records[source]=rows
            receipts['sources'][source]=now.isoformat()
            self.save_receipts(receipts,records)
            private_write(self.directory/f'{source}-events.jsonl',data)
            return {'accepted':len(accepted),'duplicates':duplicates}

    def remove_session(self, session_id):
        """Explicit maintenance for one exact session; no heuristic or text matching."""
        from uuid import UUID
        canonical = str(UUID(session_id))
        removed = {}
        with self.locked():
            records = self.read()
            for source, rows in records.items():
                retained = [row for row in rows if not row.get('sessionId') or str(UUID(row['sessionId'])) != canonical]
                count = len(rows)-len(retained)
                if count:
                    # Data retained privately for reversible repair, outside collector input paths.
                    backup = self.directory/'.maintenance'/f'{source}-{secrets.token_hex(8)}.jsonl'
                    data = ''.join(json.dumps(r,separators=(',',':'))+'\n' for r in rows).encode()
                    private_write(backup,data)
                    data = ''.join(json.dumps(r,separators=(',',':'))+'\n' for r in retained).encode()
                    private_write(self.directory/f'{source}-events.jsonl',data)
                removed[source] = count
        return {'removedBySource':removed,'backupRetainedPrivately':True}

    def health(self, now=None):
        now = now or dt.datetime.now(dt.timezone.utc)
        with self.locked():
            records = self.read()
            receipts = self.receipt_metadata(records,now)
            cutoff = now-dt.timedelta(days=self.retention_days)
            for source, rows in records.items():
                retained = [row for row in rows if VALIDATOR.timestamp(row['occurredAt']) >= cutoff and VALIDATOR.timestamp(receipts['firstSeen'][row['eventId']])>=cutoff]
                if len(retained) != len(rows):
                    data = ''.join(json.dumps(r,separators=(',',':'),ensure_ascii=False)+'\n' for r in retained).encode()
                    private_write(self.directory/f'{source}-events.jsonl',data)
                records[source] = retained
            self.save_receipts(receipts,records)
        sources = {}
        for source, rows in records.items():
            future, safe = [], []
            for row in rows:
                target = future if any(row.get(key) and VALIDATOR.timestamp(row[key])>now for key in ('occurredAt','startedAt','endedAt')) else safe
                target.append(row)
            latest = max((VALIDATOR.timestamp(row['occurredAt']) for row in safe),default=None)
            reported = max((VALIDATOR.timestamp(row['occurredAt']) for row in rows),default=None)
            latest_future = max((VALIDATOR.timestamp(row[key]) for row in future for key in ('occurredAt','startedAt','endedAt') if row.get(key) and VALIDATOR.timestamp(row[key])>now),default=None)
            age = (now-latest).total_seconds()/3600 if latest else None
            sources[source] = {'events':len(rows),'futureEventCount':len(future),
                               'lastSuccessfulReceiptAt':receipts['sources'].get(source),
                               'latestObservedAt':latest.isoformat() if latest else None,
                               'latestReportedAt':reported.isoformat() if reported else None,
                               'latestFutureAt':latest_future.isoformat() if latest_future else None,
                               'eventFreshness':'clock_skew' if future else ('no_data' if latest is None else ('stale' if age>36 else 'recent'))}
        return {'status':'ok','bind':HOST,'retentionDays':self.retention_days,'sources':sources,
                'clockMeaning':'Future records are diagnostic only, not recent activity. Upload receipt does not prove work.'}



class ConflictError(ValueError):
    pass


class Receiver(http.server.ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, store, token, port=PORT):
        if not isinstance(token,str) or len(token) < 32:
            raise ValueError('strong token required')
        self.store, self.token = store, token
        self.last_retention = 0
        super().__init__((HOST,port),Handler)

    def service_actions(self):
        if time.monotonic()-self.last_retention >= 3600:
            try:
                self.store.health()
            except (OSError,ValueError,UnicodeError):
                pass  # Endpoint reports storage_unavailable; never print record contents.
            self.last_retention = time.monotonic()


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = 'FocusDeckIngestion/1'
    sys_version = ''

    def log_message(self, format, *args):
        pass

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def respond(self,status,body):
        data = json.dumps(body,separators=(',',':')).encode()
        self.send_response(status)
        self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(data)))
        self.send_header('Cache-Control','no-store')
        self.send_header('Connection','close')
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True

    def authorized(self):
        auth = self.headers.get('Authorization','')
        return hmac.compare_digest(auth.encode('utf-8'), ('Bearer '+self.server.token).encode('utf-8'))

    def do_GET(self):
        if not self.authorized(): return self.respond(401,{'error':'unauthorized'})
        if self.path != '/v1/health': return self.respond(404,{'error':'not_found'})
        try:
            return self.respond(200,self.server.store.health())
        except (ValueError,OSError,UnicodeError):
            return self.respond(503,{'error':'storage_unavailable'})

    def do_POST(self):
        if not self.authorized(): return self.respond(401,{'error':'unauthorized'})
        if self.path != '/v1/events': return self.respond(404,{'error':'not_found'})
        if self.headers.get('Content-Type','').split(';')[0].strip() != 'application/json':
            return self.respond(415,{'error':'json_required'})
        if self.headers.get('Transfer-Encoding') or self.headers.get('Content-Encoding'):
            return self.respond(400,{'error':'unsupported_encoding'})
        try:
            length = int(self.headers.get('Content-Length',''))
        except ValueError:
            return self.respond(411,{'error':'length_required'})
        if length <= 0 or length > MAX_BODY:
            return self.respond(413,{'error':'body_limit'})
        try:
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ValueError('truncated body')
            body = json.loads(raw)
            if not isinstance(body,dict) or type(body.get('schemaVersion')) is not int or body['schemaVersion'] != 1:
                raise ValueError('envelope')
            result = self.server.store.ingest(body['events'])
        except ConflictError:
            return self.respond(409,{'error':'event_id_conflict'})
        except (ValueError,KeyError,TypeError,UnicodeError):
            return self.respond(400,{'error':'invalid_events'})
        except OSError:
            return self.respond(503,{'error':'storage_unavailable'})
        return self.respond(202,result)


def prepare(directory, port=PORT):
    directory = absolute(directory)
    root = SCRIPT.parent.parent
    if (root/'settings.gradle.kts').exists() and (directory == root or root in directory.parents):
        raise ValueError('receiver secrets must be outside repository')
    if any(part in ('LaunchAgents','LaunchDaemons') for part in directory.parts):
        raise ValueError('prepare does not install agents')
    config_path = directory/'receiver-config.json'
    if config_path.exists() or (directory/'receiver.token').exists() or (directory/'runtime/receiver.py').exists():
        raise FileExistsError('existing receiver configuration or token preserved')
    if not 1024 <= port <= 65535: raise ValueError('unprivileged port required')
    directory.mkdir(mode=0o700,parents=True,exist_ok=True)
    os.chmod(directory,0o700)
    runtime = directory/'runtime'
    runtime.mkdir(mode=0o700,exist_ok=True)
    shared = runtime/'shared'
    shared.mkdir(mode=0o700,exist_ok=True)
    files = {'receiver.py':SCRIPT, 'shared/validate_events.py':pathlib.Path(VALIDATOR.__file__),
             'shared/event-v1.schema.json':pathlib.Path(VALIDATOR.__file__).with_name('event-v1.schema.json')}
    hashes = {}
    for name, source in files.items():
        content = source.read_bytes()
        private_write(runtime/name,content)
        hashes[name] = hashlib.sha256(content).hexdigest()
    token_path = directory/'receiver.token'
    private_write(token_path,(secrets.token_urlsafe(48)+'\n').encode())
    config = {'schemaVersion':1,'host':HOST,'port':port,'tokenFile':str(token_path),
              'inbox':str(directory/'inbox'),'retentionDays':90,'runtimeHashes':hashes,
              'runtimeDirectory':str(runtime),'externalMessages':False}
    EventStore(config['inbox'])
    private_write(config_path,(json.dumps(config,indent=2)+'\n').encode())
    job = {'Label':LABEL,'ProgramArguments':[str(pathlib.Path(sys.executable).resolve()),str(runtime/'receiver.py'),'serve','--config',str(config_path)],
           'RunAtLoad':True,'KeepAlive':{'SuccessfulExit':False},'ThrottleInterval':10,'Umask':63,
           'StandardOutPath':str(directory/'receiver.stdout.log'),'StandardErrorPath':str(directory/'receiver.stderr.log')}
    private_write(directory/'receiver.plist',plistlib.dumps(job))
    return {'config':str(config_path),'plist':str(directory/'receiver.plist'),'host':HOST,'port':port,
            'tokenFile':str(token_path),'inbox':config['inbox'],'active':False}


def verify_runtime(config):
    runtime = absolute(config['runtimeDirectory'])
    if not config.get('runtimeHashes'):
        raise ValueError('runtime manifest required')
    for name, digest in config['runtimeHashes'].items():
        path = (runtime/name).resolve()
        if runtime not in path.parents or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('runtime integrity check failed')


def service_status(directory):
    directory = absolute(directory)
    installed = pathlib.Path.home()/'Library/LaunchAgents'/f'{LABEL}.plist'
    result = subprocess.run(['/bin/launchctl','print',f'gui/{os.getuid()}/{LABEL}'],capture_output=True,text=True)
    return {'installed':installed.exists(),'loaded':result.returncode == 0,
            'config':str(directory/'receiver-config.json'),
            'launchctl':result.stdout if result.returncode == 0 else result.stderr}


def service_install(directory):
    directory = absolute(directory)
    config = json.loads((directory/'receiver-config.json').read_text())
    if config.get('host') != HOST: raise ValueError('loopback-only binding required')
    verify_runtime(config)
    installed = pathlib.Path.home()/'Library/LaunchAgents'/f'{LABEL}.plist'
    if installed.exists():
        raise FileExistsError('existing installed receiver preserved; uninstall it explicitly first')
    with (directory/'receiver.plist').open('rb') as stream:
        job = plistlib.load(stream)
    if job.get('Label') != LABEL or str(directory/'runtime/receiver.py') not in job.get('ProgramArguments',[]):
        raise ValueError('receiver plist does not belong to private directory')
    installed.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(directory/'receiver.plist',installed)
    os.chmod(installed,0o600)
    subprocess.run(['/bin/launchctl','bootstrap',f'gui/{os.getuid()}',str(installed)],check=True,capture_output=True,text=True)
    return service_status(directory)


def service_upgrade(directory):
    directory=absolute(directory)
    config_path=directory/'receiver-config.json'
    config=json.loads(config_path.read_text())
    runtime=directory/'runtime'
    installed=pathlib.Path.home()/'Library/LaunchAgents'/f'{LABEL}.plist'
    if absolute(config['runtimeDirectory'])!=runtime or not installed.exists():raise ValueError('owned installed receiver required')
    with installed.open('rb') as stream:job=plistlib.load(stream)
    if job.get('Label')!=LABEL or str(runtime/'receiver.py') not in job.get('ProgramArguments',[]):raise ValueError('unrelated job preserved')
    verify_runtime(config)
    stage=pathlib.Path(tempfile.mkdtemp(dir=directory,prefix='.receiver-stage-'))
    try:
        (stage/'shared').mkdir(mode=0o700)
        sources={'receiver.py':SCRIPT,'shared/validate_events.py':pathlib.Path(VALIDATOR.__file__),
                 'shared/event-v1.schema.json':pathlib.Path(VALIDATOR.__file__).with_name('event-v1.schema.json')}
        hashes={}
        for name,source in sources.items():
            content=source.read_bytes()
            if name.endswith('.py'):compile(content,name,'exec')
            private_write(stage/name,content);hashes[name]=hashlib.sha256(content).hexdigest()
        verify_runtime(dict(config,runtimeDirectory=str(stage),runtimeHashes=hashes))
        backups=directory/'.upgrades';backups.mkdir(mode=0o700,exist_ok=True)
        backup=pathlib.Path(tempfile.mkdtemp(dir=backups,prefix='receiver-'))
        private_write(backup/'receiver-config.json',config_path.read_bytes())
        loaded=subprocess.run(['/bin/launchctl','print',f'gui/{os.getuid()}/{LABEL}'],capture_output=True,text=True).returncode==0
        if loaded:subprocess.run(['/bin/launchctl','bootout',f'gui/{os.getuid()}/{LABEL}'],check=True,capture_output=True,text=True)
        switched=False
        try:
            runtime.rename(backup/'runtime');stage.rename(runtime);switched=True
            config['runtimeHashes']=hashes
            private_write(config_path,(json.dumps(config,indent=2)+'\n').encode())
            if loaded:subprocess.run(['/bin/launchctl','bootstrap',f'gui/{os.getuid()}',str(installed)],check=True,capture_output=True,text=True)
            return {'upgraded':True,'loadedBeforeUpgrade':loaded,'backup':str(backup),'tokenAndInboxPreserved':True}
        except Exception as error:
            subprocess.run(['/bin/launchctl','bootout',f'gui/{os.getuid()}/{LABEL}'],capture_output=True,text=True)
            if switched:runtime.rename(backup/'failed-runtime')
            if (backup/'runtime').exists():(backup/'runtime').rename(runtime)
            private_write(config_path,(backup/'receiver-config.json').read_bytes())
            if loaded:subprocess.run(['/bin/launchctl','bootstrap',f'gui/{os.getuid()}',str(installed)],capture_output=True,text=True)
            raise ValueError('upgrade failed; prior files restored; verify status') from error
    finally:
        if stage.exists():shutil.rmtree(stage)


def service_uninstall(directory):
    directory = absolute(directory)
    installed = pathlib.Path.home()/'Library/LaunchAgents'/f'{LABEL}.plist'
    if installed.exists():
        with installed.open('rb') as stream:
            job = plistlib.load(stream)
        if job.get('Label') != LABEL or str(directory/'runtime/receiver.py') not in job.get('ProgramArguments',[]):
            raise ValueError('refusing to remove unrelated receiver job')
        subprocess.run(['/bin/launchctl','bootout',f'gui/{os.getuid()}/{LABEL}'],capture_output=True,text=True)
        installed.unlink()
    return {'installed':False,'dataRetained':str(directory),'tokenRetained':True,'externalMessagesSent':0}


def serve(config_path):
    os.umask(0o077)
    config = json.loads(absolute(config_path).read_text())
    if config.get('host') != HOST: raise ValueError('loopback-only binding required')
    verify_runtime(config)
    token_path = absolute(config['tokenFile'])
    if token_path.stat().st_mode & 0o077:
        raise ValueError('token file permissions must be owner-only')
    token = token_path.read_text().strip()
    store = EventStore(config['inbox'],config['retentionDays'])
    Receiver(store,token,config['port']).serve_forever(poll_interval=0.5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command',required=True)
    generate = commands.add_parser('prepare')
    generate.add_argument('--directory',required=True)
    generate.add_argument('--port',type=int,default=PORT)
    cleanup = commands.add_parser('remove-session')
    cleanup.add_argument('--config',required=True)
    cleanup.add_argument('--session-id',required=True)
    for name in ('install','uninstall','status','upgrade'):
        service = commands.add_parser(name)
        service.add_argument('--directory',required=True)
    run = commands.add_parser('serve')
    run.add_argument('--config',required=True)
    args = parser.parse_args()
    try:
        if args.command == 'prepare': print(json.dumps(prepare(args.directory,args.port),indent=2))
        elif args.command == 'serve': serve(args.config)
        elif args.command == 'remove-session':
            config = json.loads(absolute(args.config).read_text())
            result = EventStore(config['inbox'],config['retentionDays']).remove_session(args.session_id)
            print(json.dumps(result,indent=2))
        else: print(json.dumps({'install':service_install,'uninstall':service_uninstall,'status':service_status,'upgrade':service_upgrade}[args.command](args.directory),indent=2))
    except (ValueError,OSError,KeyError,subprocess.SubprocessError) as error:
        parser.exit(1,f'{type(error).__name__}: receiver setup failed\n')


if __name__ == '__main__': main()
