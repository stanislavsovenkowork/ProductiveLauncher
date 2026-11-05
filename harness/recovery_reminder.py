"""Opt-in local reminder decision engine; caller supplies a notification host.

No host or scheduler is installed here. A host must request/report macOS permission,
read an explicitly selected private next-action source, display the returned message,
and open the context only after the user selects its notification action. The host
must not open an application in response to evaluate(). The engine reserves an
attempt before calling the host: a crash can skip a reminder, but cannot repeat it.
"""
import datetime as dt
import fcntl
import json
import os
import pathlib
import tempfile
from zoneinfo import ZoneInfo

UTC = dt.timezone.utc
COOLDOWN = dt.timedelta(hours=24)
MAX_ATTEMPTS = 3
ZONE = ZoneInfo('Europe/Minsk')


def _instant(value):
    if not isinstance(value, str): raise ValueError('timestamp required')
    parsed = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    if parsed.tzinfo is None: raise ValueError('timezone required')
    return parsed.astimezone(UTC)


def _load(path, default):
    try:
        value = json.loads(path.read_text())
        if not isinstance(value, dict): raise ValueError('object required')
        return value
    except FileNotFoundError:
        return default


def _save(path, value):
    fd, temporary = tempfile.mkstemp(dir=path.parent, prefix='.reminder-')
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, ensure_ascii=False); stream.write('\n')
            stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try: os.fsync(directory)
        finally: os.close(directory)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


def evaluate(directory, *, opted_in, permission, recovery, next_action, deliver, now=None):
    """Permission: authorized/denied/not_determined/unknown. No implicit consent.

    Returns an honest local decision, never a claim of actual banner delivery.
    The caller must provide a current, evaluated recovery report and an explicit
    local next-action string; raw task IDs and note text are not retained here.
    """
    now = now or dt.datetime.now(UTC)
    if now.tzinfo is None: raise ValueError('now must include timezone')
    now = now.astimezone(UTC)
    if permission not in ('authorized', 'denied', 'not_determined', 'unknown'): raise ValueError('invalid permission')
    if opted_in is not True: return {'state':'disabled', 'permission':permission}
    if permission != 'authorized': return {'state':'permission_required', 'permission':permission}
    if not isinstance(recovery, dict) or recovery.get('enabled') is not True or recovery.get('state') != 'source_unavailable':
        return {'state':'insufficient_evidence', 'permission':permission}
    try:
        evaluated = _instant(recovery.get('evaluatedAtUTC'))
        observed = _instant(recovery.get('lastObservedAtUTC'))
        context = recovery.get('context')
        context_at = _instant(context.get('occurredAtUTC')) if isinstance(context, dict) else None
        if not (observed <= evaluated <= now and context_at <= evaluated and now-evaluated <= dt.timedelta(hours=1)):
            raise ValueError('stale or future evidence')
        if not context.get('taskRef') or not isinstance(next_action, str) or not next_action.strip() or len(next_action) > 160:
            raise ValueError('explicit context and next action required')
    except (ValueError, TypeError, AttributeError):
        return {'state':'insufficient_evidence', 'permission':permission}
    local = now.astimezone(ZONE)
    if local.hour >= 22 or local.hour < 8:
        return {'state':'quiet_hours', 'permission':permission}
    root = pathlib.Path(directory)
    if not root.is_absolute() or not root.is_dir(): raise ValueError('explicit existing absolute directory required')
    fd = os.open(root/'.recovery-reminder.lock', os.O_CREAT | os.O_RDWR, 0o600)
    with os.fdopen(fd, 'r+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        path = root/'recovery-reminder-state.json'
        state = _load(path, {'schemaVersion':1, 'attempts':0, 'lastAttemptAtUTC':None, 'episodeObservedAtUTC':None})
        if state.get('schemaVersion') != 1 or type(state.get('attempts')) is not int or not 0 <= state['attempts'] <= MAX_ATTEMPTS:
            raise ValueError('invalid reminder state')
        last = _instant(state['lastAttemptAtUTC']) if state.get('lastAttemptAtUTC') else None
        if last and (last > now or now-last < COOLDOWN): return {'state':'cooldown', 'permission':permission}
        # A newly observed event starts a new bounded episode; no reset on retries.
        previous_observed = _instant(state['episodeObservedAtUTC']) if state.get('episodeObservedAtUTC') else None
        attempts = 0 if previous_observed is None or observed > previous_observed else state['attempts']
        if attempts >= MAX_ATTEMPTS: return {'state':'episode_limit', 'permission':permission}
        _save(path, {'schemaVersion':1, 'attempts':attempts+1, 'lastAttemptAtUTC':now.isoformat(), 'episodeObservedAtUTC':observed.isoformat()})
        # The host is responsible for explicit action handling and permission status.
        try:
            deliver({'title':'Можно вернуться к задаче', 'body':next_action.strip(),
                     'action':'open_context', 'explanation':'Нет недавних событий; причина неизвестна.'})
        except Exception:
            return {'state':'host_error', 'permission':permission, 'attemptReserved':True}
        return {'state':'dispatch_attempted', 'permission':permission, 'attemptReserved':True,
                'delivery':'unknown'}


def configure(config_path, *, host, context, app):
    path=pathlib.Path(config_path)
    if not path.is_absolute(): raise ValueError('explicit absolute config required')
    config=_load(path,{})
    paths=[pathlib.Path(x) for x in (host,context,app)]
    if not all(p.is_absolute() for p in paths) or not paths[0].is_file() or not paths[2].is_dir() or paths[2].suffix != '.app':
        raise ValueError('invalid explicit host, context or app')
    if 'recoveryReminder' in config: return {'configured':True, 'changed':False}
    config['recoveryReminder']={'enabled':False,'hostExecutable':str(paths[0]), 'contextPath':str(paths[1]), 'openAppPath':str(paths[2])}
    _save(path,config)
    return {'configured':True, 'changed':True, 'enabled':False}


def control(config_path, action, *, remove_host=True):
    """Explicit local control. stop disables before removing only this host's notices."""
    import subprocess
    path=pathlib.Path(config_path)
    if not path.is_absolute(): raise ValueError('explicit absolute config required')
    config=_load(path, {})
    options=config.get('recoveryReminder')
    if not isinstance(options,dict): raise ValueError('reminder configuration required')
    host=pathlib.Path(options.get('hostExecutable',''))
    output=pathlib.Path(config.get('outputDirectory',''))
    if not host.is_absolute() or not output.is_absolute() or not output.is_dir():
        raise ValueError('configured host or output unavailable')
    if action=='status':
        try:
            result=subprocess.run([str(host),'status'],capture_output=True,text=True,timeout=8,check=True)
            permission=result.stdout.strip()
            if permission not in ('authorized','denied','not_determined','unknown'): permission='unknown'
        except (OSError,subprocess.SubprocessError): permission='unknown'
        return {'enabled':options.get('enabled') is True, 'permission':permission}
    if action not in ('enable','stop'): raise ValueError('unknown control')
    fd=os.open(output/'.recovery-reminder.lock',os.O_CREAT|os.O_RDWR,0o600)
    with os.fdopen(fd,'r+') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        config=_load(path,{})
        if not isinstance(config.get('recoveryReminder'),dict): raise ValueError('reminder configuration required')
        config['recoveryReminder']['enabled']=action=='enable'
        _save(path,config)
        if action=='stop':
            if not remove_host: return {'enabled':False,'removal':'unknown'}
            try:
                subprocess.run([str(host),'stop'],check=True,capture_output=True,timeout=8)
                return {'enabled':False,'removal':'requested'}
            except (OSError,subprocess.SubprocessError): return {'enabled':False,'removal':'unknown'}
        return {'enabled':True,'permission':'not_requested'}


if __name__ == '__main__':
    import argparse
    parser=argparse.ArgumentParser(description='Control local recovery reminders; no implicit permission request.')
    parser.add_argument('action',choices=['configure','enable','stop','status'])
    parser.add_argument('--config',required=True)
    parser.add_argument('--host'); parser.add_argument('--context'); parser.add_argument('--app'); parser.add_argument('--no-host',action='store_true')
    args=parser.parse_args()
    result=configure(args.config,host=args.host,context=args.context,app=args.app) if args.action=='configure' else control(args.config,args.action,remove_host=not args.no_host)
    print(json.dumps(result,ensure_ascii=False))
