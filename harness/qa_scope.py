"""Explicit private QA scope markers. No app names or event contents are recorded."""
import argparse
import datetime as dt
import fcntl
import json
import os
import pathlib
import uuid

UTC=dt.timezone.utc

def append(path, kind, scope_id, now=None):
    path=pathlib.Path(path)
    if not path.is_absolute() or not path.parent.is_dir(): raise ValueError('absolute path with existing parent required')
    scope_id=str(uuid.UUID(scope_id)); now=now or dt.datetime.now(UTC)
    if now.tzinfo is None: raise ValueError('timezone required')
    fd=os.open(path,os.O_RDWR|os.O_CREAT|os.O_APPEND,0o600)
    with os.fdopen(fd,'r+b') as f:
        fcntl.flock(f,fcntl.LOCK_EX); f.seek(0); data=f.read()
        if data and not data.endswith(b'\n'): raise ValueError('incomplete metadata')
        rows=[json.loads(x) for x in data.splitlines()]
        own=[r for r in rows if r.get('scopeId')==scope_id]
        if kind=='start' and own: raise ValueError('scope already exists')
        if kind=='end' and (len(own)!=1 or own[0].get('event')!='start'): raise ValueError('scope not open')
        row={'schemaVersion':1,'event':kind,'scopeId':scope_id,'at':now.astimezone(UTC).isoformat()}
        if not data: row['coverageFrom']=row['at']
        f.seek(0,os.SEEK_END); f.write((json.dumps(row,separators=(',',':'))+'\n').encode()); f.flush(); os.fsync(f.fileno())


def main():
    import subprocess
    p=argparse.ArgumentParser(); p.add_argument('event',choices=['init','start','end','run']); p.add_argument('--log',required=True); p.add_argument('--id')
    a,command=p.parse_known_args()
    if a.event=='init':
        path=pathlib.Path(a.log)
        if path.exists(): raise ValueError('metadata already exists; preserved')
        ident=str(uuid.uuid4()); append(path,'start',ident); append(path,'end',ident); return
    if a.event!='run':
        if not a.id: p.error('--id required')
        append(a.log,a.event,a.id); return
    command=command[1:] if command and command[0]=='--' else command
    if not command: p.error('command required')
    ident=str(uuid.uuid4()); append(a.log,'start',ident)
    try: status=subprocess.run(command).returncode
    except BaseException:
        # Unknown child state; preserve the open scope for explicit resolution.
        raise
    if status != 75: append(a.log,'end',ident)
    raise SystemExit(status)
if __name__=='__main__':main()
