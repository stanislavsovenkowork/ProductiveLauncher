#!/usr/bin/env python3
"""Opt-in local return context, evaluated by the existing review; no notifications."""
import argparse
import datetime as dt
import fcntl
import json
import os
import pathlib
import re
import tempfile

from metrics import instant, read_lines, validate

UTC = dt.timezone.utc
LIFECYCLE = {'started', 'paused', 'resumed', 'completed', 'stopped', 'missed', 'disabled', 'uninstalled'}


def save(path, value):
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix='.recovery-')
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)


def read(path, default):
    try:
        value = json.loads(path.read_text())
        return value if isinstance(value, dict) else default
    except (OSError, ValueError):
        return default


def location(config):
    path = pathlib.Path(config['outputDirectory'])
    if not path.is_absolute(): raise ValueError('outputDirectory must be absolute')
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    return path


def locked(output):
    fd = os.open(output/'.recovery.lock', os.O_CREAT | os.O_RDWR, 0o600)
    stream = os.fdopen(fd, 'r+')
    fcntl.flock(stream, fcntl.LOCK_EX)
    return stream


def publish(output, result, event=None):
    history = output/'recovery-history.jsonl'
    if event:
        fd = os.open(history, os.O_APPEND | os.O_CREAT | os.O_WRONLY, 0o600)
        with os.fdopen(fd, 'a') as stream:
            stream.write(json.dumps(event, ensure_ascii=False, separators=(',', ':'))+'\n')
    try:
        with history.open() as stream: count = sum(1 for _ in stream)
    except FileNotFoundError: count = 0
    result['historyCount'] = count
    save(output/'recovery.json', result)
    return result


def configure(config, enabled, index=None, stale_hours=None, now=None):
    now = now or dt.datetime.now(UTC)
    output = location(config)
    with locked(output):
        previous = read(output/'recovery.json', {})
        old_control = read(output/'recovery-control.json', {})
        if enabled:
            roots = config.get('vaultTelemetryRoots', [])
            if type(index) is not int or index < 0 or index >= len(roots):
                raise ValueError('select an existing explicit vaultTelemetryRoots index')
            if type(stale_hours) not in (int, float) or not 1 <= stale_hours <= 720:
                raise ValueError('stale hours must be between 1 and 720')
            root = roots[index]
            control = {'enabled': True, 'sourceIndex': index, 'sourceRoot': root, 'staleAfterHours': stale_hours}
            if old_control.get('sourceRoot') != root:
                previous = {}
            state, explanation = 'unknown', 'Waiting for the next local review; source status is unknown.'
        else:
            control = dict(old_control, enabled=False)
            state, explanation = 'disabled', 'Stopped by user; no further recovery checks or reminders.'
        save(output/'recovery-control.json', control)
        result = {'schemaVersion': 1, 'enabled': enabled, 'state': state,
                  'evaluatedAtUTC': now.isoformat(), 'lastObservedAtUTC': previous.get('lastObservedAtUTC'),
                  'context': previous.get('context'), 'explanation': explanation}
        return publish(output, result, {'atUTC': now.isoformat(), 'event': 'enabled' if enabled else 'disabled',
                                         **({'sourceIndex': index, 'staleAfterHours': stale_hours} if enabled else {})})


def evaluate(config, health, now=None):
    now = now or dt.datetime.now(UTC)
    output = location(config)
    with locked(output):
        control = read(output/'recovery-control.json', {})
        if control.get('enabled') is not True:
            return read(output/'recovery.json', {'schemaVersion': 1, 'enabled': False, 'state': 'disabled'})
        previous = read(output/'recovery.json', {})
        idx = control.get('sourceIndex')
        roots = config.get('vaultTelemetryRoots', [])
        threshold = control.get('staleAfterHours')
        valid = type(idx) is int and 0 <= idx < len(roots) and roots[idx] == control.get('sourceRoot') and type(threshold) in (int, float) and 1 <= threshold <= 720
        source_health = health.get(f'vaultSync{idx}', {}) if valid else {}
        rows, cache_health = read_lines(output/f'vault-android-{idx}.jsonl') if valid else ([], {})
        accepted = []
        for row in rows:
            try:
                row = validate(row)
                task = row.get('taskRef')
                if (row['source'] == 'android' and instant(row['occurredAt']) <= now
                        and (task is None or re.fullmatch(r'[A-Za-z0-9_-]{1,128}', task))):
                    accepted.append(row)
            except (ValueError, KeyError, TypeError):
                continue
        accepted.sort(key=lambda row: instant(row['occurredAt']))
        latest = accepted[-1] if accepted else None
        context = previous.get('context')
        last = previous.get('lastObservedAtUTC')
        if latest:
            stamp = latest['occurredAt']
            if not last or instant(stamp) >= instant(last): last = stamp
            session_row = next((r for r in reversed(accepted) if r.get('sessionId')), None)
            previous_context_at = (context or {}).get('occurredAtUTC')
            if session_row and (not previous_context_at or instant(session_row['occurredAt']) >= instant(previous_context_at)):
                session = session_row['sessionId']
                session_events = [r for r in accepted if r.get('sessionId') == session]
                lifecycle = next((r for r in reversed(session_events) if r['type'] in LIFECYCLE), None)
                task = next((r.get('taskRef') for r in reversed(session_events) if r.get('taskRef')), None)
                context = {'sessionId': session, 'taskRef': task,
                           'sessionState': lifecycle['type'] if lifecycle else 'unknown',
                           'occurredAtUTC': session_row['occurredAt']}
        if not valid or cache_health.get('state') != 'present':
            state = 'unknown'
            explanation = 'Selected source or verified cache is unavailable; the cause is unknown.'
        elif source_health.get('state') != 'observed' or not latest or (now-instant(latest['occurredAt'])).total_seconds() > threshold*3600:
            state = 'source_unavailable'
            explanation = 'No recent verified Android events or source unavailable. Offline, permission changes and other causes are possible; deletion is not established.'
        else:
            state = 'available'
            explanation = 'Recent Android events received; this does not prove live connectivity or current session state.'
        result = {'schemaVersion': 1, 'enabled': True, 'state': state, 'evaluatedAtUTC': now.isoformat(),
                  'lastObservedAtUTC': last, 'context': context, 'explanation': explanation}
        event = {'atUTC': now.isoformat(), 'event': 'state_changed', 'state': state} if previous.get('state') != state else None
        return publish(output, result, event)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['enable', 'disable', 'status'])
    parser.add_argument('--config', required=True, type=pathlib.Path)
    parser.add_argument('--source-index', type=int)
    parser.add_argument('--stale-hours', type=float)
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    if args.action == 'status':
        result = read(location(config)/'recovery.json', {'schemaVersion': 1, 'enabled': False, 'state': 'disabled'})
    else:
        result = configure(config, args.action == 'enable', args.source_index, args.stale_hours)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__': main()
