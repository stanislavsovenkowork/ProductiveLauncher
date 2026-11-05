#!/usr/bin/env python3
"""Collect only explicitly selected immutable FocusDeck telemetry batches, never vault notes."""
import argparse
import hashlib
import importlib.util
import json
import os
import pathlib
import re
import stat
import tempfile
import uuid

MAX_BYTES=128*1024
MAX_EVENTS=256
NAME=re.compile(r'^([0-9a-f]{64})(?:\.retry[1-3])?\.jsonl$')
FIELDS={'schemaVersion','eventId','sessionId','occurredAt','source','category','type','evidence','metrics',
        'startedAt','endedAt','coverage','reason','taskRef','protectionId'}
REASONS={'clock_or_process_gap','accessibility_connected','accessibility_disconnected','user_disabled',
 'launcher_attempt_denied','system_app_denied','bounded_relief_started','rest_without_feeds','rest_returned','bounded_relief_expired','relief_ended_early','ritual_optout','window_started','protection_started','protection_expired','protection_exit',
 'dns_consent_declined','dns_consent_required','dns_future_windows_disabled','dns_refused_existing_vpn','dns_refused_network_or_private_dns',
 'dns_refused_notifications','dns_service_unavailable','dns_settings_incomplete','dns_settings_unavailable','dns_snapshot_mismatch',
 'dns_trial_active_partial','dns_trial_inactive','dns_preflight_unavailable','dns_refused_safety_scope'}
root=pathlib.Path(__file__).resolve().parent
validator_path=next((p for p in (root.parent/'shared/validate_events.py',root/'shared/validate_events.py') if p.is_file()),None)
if validator_path is None:raise ValueError('shared validator unavailable')
spec=importlib.util.spec_from_file_location('vault_event_validator',validator_path)
validator=importlib.util.module_from_spec(spec);spec.loader.exec_module(validator)


def producer(value):
    try:return str(uuid.UUID(value))==value
    except (ValueError,TypeError):return False


def read_batch(path):
    flags=os.O_RDONLY|getattr(os,'O_NOFOLLOW',0)
    fd=os.open(path,flags)
    try:
        before=os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_nlink!=1 or not 0<before.st_size<=MAX_BYTES:raise ValueError('batch size/type')
        with os.fdopen(fd,'rb',closefd=False) as stream:data=stream.read(MAX_BYTES+1)
        after=os.fstat(fd)
        if (before.st_ino,before.st_size,before.st_mtime_ns)!=(after.st_ino,after.st_size,after.st_mtime_ns):raise ValueError('batch still changing')
    finally:os.close(fd)
    match=NAME.fullmatch(path.name)
    if match is None or hashlib.sha256(data).hexdigest()!=match.group(1) or not data.endswith(b'\n'):raise ValueError('incomplete/hash mismatch')
    lines=[line for line in data.decode('utf-8').splitlines() if line.strip()]
    if not 0<len(lines)<=MAX_EVENTS:raise ValueError('event count')
    rows=[]
    for line in lines:
        row=validator.validate(json.loads(line))
        if row['source']!='android' or set(row)-FIELDS:raise ValueError('source/privacy fields')
        if row.get('reason') is not None and row['reason'] not in REASONS:
            row=dict(row);row.pop('reason',None) # Unknown free text never copied; valid later events still flow.
        if row.get('taskRef') is not None and not re.fullmatch(r'[A-Za-z0-9_-]{1,128}',row['taskRef']):raise ValueError('task ID')
        if row.get('protectionId') is not None and not producer(row['protectionId']):raise ValueError('protection ID')
        rows.append(row)
    return rows


def collect(path):
    path=pathlib.Path(path)
    if not path.is_absolute() or tuple(path.parts[-2:])!=('events','android'):raise ValueError('explicit events/android root required')
    health={'state':'missing','validBatches':0,'invalidBatches':0,'ignoredTemporaryOrConflictFiles':0,'producers':0,
            'events':0,'duplicateEvents':0,'conflictingEventIds':0,'transport':'existing_syncthing',
            'meaning':'Local validated receipt only; live connection and remote completeness unknown.'}
    if not path.exists():return [],health
    if path.is_symlink() or not path.is_dir():health['state']='unreadable';return [],health
    seen,conflicts={},set()
    try:
        for directory in sorted(path.iterdir()):
            if not producer(directory.name):continue
            if directory.is_symlink() or not directory.is_dir():health['invalidBatches']+=1;continue
            health['producers']+=1
            if health['producers']>256:health['invalidBatches']+=1;break
            for batch in sorted(directory.iterdir()):
                if not NAME.fullmatch(batch.name):health['ignoredTemporaryOrConflictFiles']+=1;continue
                if health['validBatches']+health['invalidBatches']>=10000:health['invalidBatches']+=1;break
                try:rows=read_batch(batch)
                except (ValueError,OSError,UnicodeError,KeyError,TypeError):health['invalidBatches']+=1;continue
                health['validBatches']+=1
                for row in rows:
                    identity=row['eventId']
                    if identity in seen:
                        if seen[identity]!=row:conflicts.add(identity)
                        else:health['duplicateEvents']+=1
                    else:seen[identity]=row
    except OSError:health['state']='unreadable';return [],health
    rows=[row for identity,row in seen.items() if identity not in conflicts]
    health['events']=len(rows);health['conflictingEventIds']=len(conflicts)
    health['state']='partial' if health['invalidBatches'] or conflicts or health['ignoredTemporaryOrConflictFiles'] else ('observed' if health['validBatches'] else 'empty')
    return sorted(rows,key=lambda row:(row['occurredAt'],row['eventId'])),health


def save(path,content):
    path=pathlib.Path(path);path.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
    fd,temporary=tempfile.mkstemp(dir=path.parent,prefix='.vault-telemetry-')
    try:
        with os.fdopen(fd,'w') as stream:stream.write(content)
        os.replace(temporary,path)
    finally:
        if os.path.exists(temporary):os.unlink(temporary)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',required=True,type=pathlib.Path)
    parser.add_argument('--output',required=True,type=pathlib.Path)
    parser.add_argument('--health-output',required=True,type=pathlib.Path)
    args=parser.parse_args()
    if args.root.resolve() in args.output.resolve().parents or args.root.resolve() in args.health_output.resolve().parents:
        parser.error('collector outputs must be outside the producer namespace')
    rows,health=collect(args.root)
    save(args.output,''.join(json.dumps(row,separators=(',',':'))+'\n' for row in rows))
    save(args.health_output,json.dumps(health,indent=2)+'\n')
    print(json.dumps(health))


if __name__=='__main__':main()
