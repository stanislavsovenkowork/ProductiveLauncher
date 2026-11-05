#!/usr/bin/env python3
"""Prepare (never install) an opt-in launchd job, or run its local daily review."""
import argparse
import datetime as dt
import json
import hashlib
import importlib.util
import shutil
import os
import pathlib
import plistlib
import subprocess
import sys
import tempfile
from zoneinfo import ZoneInfo

SCRIPT = pathlib.Path(__file__).resolve()
REPOSITORY = SCRIPT.parent.parent if (SCRIPT.parent.parent/'settings.gradle.kts').exists() else None
LABEL = 'dev.focusdeck.local-review'


def absolute(value):
    path = pathlib.Path(value)
    if not path.is_absolute():
        raise ValueError('all paths must be absolute')
    return path.resolve()


def private_directory(value):
    path = absolute(value)
    if any(part in ('LaunchAgents','LaunchDaemons') for part in path.parts):
        raise ValueError('prepare never writes into launchd installation directories')
    if REPOSITORY and (path == REPOSITORY or REPOSITORY in path.parents):
        raise ValueError('private state and reports must be outside the repository')
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path, 0o700)
    return path


def save_private(path, data):
    """Atomic replacement; private mode applies even if an old file was public."""
    fd, temporary = tempfile.mkstemp(dir=path.parent, prefix='.focusdeck-')
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(data, stream, indent=2)
            stream.write('\n')
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def prepare(directory, events, timezone='Europe/Minsk', protocol=None, python=None, movebreak=None, movebreak_category='unknown', vault_roots=None):
    directory = private_directory(directory)
    if (directory/'review-config.json').exists() or (directory/'runtime').exists():
        raise FileExistsError('existing private configuration/runtime preserved; choose a fresh directory')
    ZoneInfo(timezone)
    python = absolute(python or sys.executable)
    if not python.is_file():
        raise ValueError('Python executable missing')
    movebreak_category = movebreak_category or 'unknown'
    if movebreak and movebreak_category not in ('work','study','unknown'):
        raise ValueError('MoveBreak category must be work/study/unknown')
    runtime = directory/'runtime'
    runtime.mkdir(mode=0o700, exist_ok=True)
    hashes = {}
    for name in ('scheduler.py','metrics.py','vault_events.py','recovery.py','recovery_reminder.py'):
        content = SCRIPT.with_name(name).read_bytes()
        target = runtime/name
        target.write_bytes(content)
        os.chmod(target, 0o600)
        hashes[name] = hashlib.sha256(content).hexdigest()
    shared = SCRIPT.parent.parent/'shared'
    if shared.exists():
        (runtime/'shared').mkdir(mode=0o700, exist_ok=True)
        for name in ('validate_events.py','event-v1.schema.json'):
            content = (shared/name).read_bytes()
            (runtime/'shared'/name).write_bytes(content)
            os.chmod(runtime/'shared'/name,0o600)
            hashes['shared/'+name] = hashlib.sha256(content).hexdigest()
    save_private(runtime/'manifest.json', hashes)
    config = {'vaultTelemetryRoots':[str(absolute(path)) for path in (vault_roots or [])], 'runtimeHashes':hashes, 'runtimeDirectory':str(runtime), 'weeklyReviewWeekday':0, 'currentDayRefreshSeconds':300, 'retentionDays':90, 'movebreak':str(absolute(movebreak)) if movebreak else None, 'movebreakCategory':movebreak_category, 'schemaVersion':1, 'timezone':timezone, 'events':[str(absolute(p)) for p in events],
              'outputDirectory':str(directory / 'reports'), 'staleAfterHours':36,
              'protocol':str(absolute(protocol)) if protocol else None,
              'enabledByUser':False, 'externalMessages':False}
    config_path = directory / 'review-config.json'
    save_private(config_path, config)
    plist = {'Label':LABEL, 'ProgramArguments':[str(python),str(runtime/'scheduler.py'),'run','--config',str(config_path)],
             'StartCalendarInterval':{'Hour':9,'Minute':0}, 'StartInterval':300, 'RunAtLoad':True,
             'StandardOutPath':str(directory/'runner.stdout.log'),
             'StandardErrorPath':str(directory/'runner.stderr.log'), 'Umask':63}
    # Saved outside LaunchAgents; prepare does not call launchctl.
    with (directory/'local-review.plist').open('wb') as stream:
        plistlib.dump(plist, stream)
    os.chmod(directory/'local-review.plist', 0o600)
    return {'config':str(config_path), 'plist':str(directory/'local-review.plist'),
            'runtime':str(runtime), 'installed':False, 'active':False,
            'note':'Inspect config, select expected sources and set enabledByUser=true before any manual installation. launchd calendar time uses the Mac system timezone; review boundaries use the configured timezone.'}


def protocol_health(path, today):
    if not path:
        return {'state':'not_configured', 'reviewDue':None, 'experimentReady':False}
    try:
        protocol = json.loads(absolute(path).read_text())
        start, end = dt.date.fromisoformat(protocol['startDate']), dt.date.fromisoformat(protocol['endDate'])
        prerequisites = ('hypothesis','baselineDates','continueCriteria','changeCriteria','stopCriteria',
                         'maintenanceBudgetMinutes','independentReturnChannel','reminderConsentAndStopRules','retentionAndRestore')
        missing = [key for key in prerequisites if protocol.get(key) is None or protocol.get(key) == '' or protocol.get(key) == []]
        state = 'review_due' if today >= end else ('before_start' if today < start else 'in_window')
        return {'state':state, 'reviewDue':today >= end, 'experimentReady':not missing,
                'criteria':{key:protocol.get(key) for key in ('continueCriteria','changeCriteria','stopCriteria','maintenanceBudgetMinutes')},
                'missingPrerequisites':missing, 'startDate':str(start), 'endDate':str(end)}
    except (OSError, ValueError, KeyError, TypeError):
        return {'state':'invalid_or_missing', 'reviewDue':None, 'experimentReady':False}


def run(config_path, now=None):
    os.umask(0o077)
    config = json.loads(absolute(config_path).read_text())
    if config.get('enabledByUser') is not True:
        raise ValueError('local review is opt-in; enabledByUser must be true')
    timezone = config.get('timezone', 'Europe/Minsk')
    zone = ZoneInfo(timezone)
    now = now or dt.datetime.now(dt.timezone.utc)
    today = now.astimezone(zone).date()
    day = today-dt.timedelta(days=1)
    output = private_directory(config['outputDirectory'])
    report_path = output / f'{day}.json'
    vault_sources,vault_health=collect_vault_sources(config,output)
    from recovery import evaluate as evaluate_recovery
    recovery_report = evaluate_recovery(config, vault_health, now)
    reminder_status = run_recovery_reminder(config, config_path, recovery_report, now)
    command = [sys.executable, str(SCRIPT.with_name('metrics.py')), 'cycle', '--day', str(day),
               '--timezone', timezone, '--as-of',now.isoformat(), '--output', str(report_path), '--qa-scopes', str(output/'qa-scopes.jsonl')]
    for path in config['events']+vault_sources:
        command.extend(['--events', str(absolute(path))])
    if config.get('movebreak'):
        if config.get('movebreakCategory') not in (None,'work','study','unknown'):
            raise ValueError('MoveBreak category required')
        command.extend(['--movebreak',str(absolute(config['movebreak'])),'--movebreak-category',config.get('movebreakCategory') or 'unknown'])
    # No shell, network, personal-vault discovery, or notification delivery.
    subprocess.run(command, check=True, capture_output=True, text=True)
    report = json.loads(report_path.read_text())
    report['vaultSyncHealth']=vault_health
    report['recoveryReminder']=reminder_status
    report['dailyCheck'] = freshness_status(report,config,now)
    report['protocolReview'] = protocol_health(config.get('protocol'), today)
    report['runtimeIntegrity'] = runtime_integrity(config)
    report['retentionDays'] = config.get('retentionDays',90)
    save_private(report_path, report)
    save_private(output/'latest.json', report)
    current_report(config,now)
    weekly_day = today-dt.timedelta(days=(today.weekday()-config.get('weeklyReviewWeekday',0)) % 7)
    # Re-evaluate the same seven-day period on each scheduled run so changes to
    # explicit sources and opt-in are reflected without creating another job.
    weekly_review(output, weekly_day, report['protocolReview'],now=now, config=config, vault_sources=vault_sources, vault_health=vault_health)
    cutoff = today-dt.timedelta(days=config.get('retentionDays',90))
    for pattern in ('????-??-??.json','weekly-????-??-??.json'):
        for candidate in output.glob(pattern):
            try:
                if dt.date.fromisoformat(candidate.stem.removeprefix('weekly-')) < cutoff:
                    candidate.unlink()
            except ValueError:
                pass
    return {'report':str(report_path), 'state':report['dailyCheck']['state'], 'reviewDue':report['protocolReview']['reviewDue']}


def run_recovery_reminder(config, config_path, recovery_report, now):
    """Use only an explicitly configured local host and context, never request permission."""
    from recovery_reminder import evaluate
    options = config.get('recoveryReminder')
    if not isinstance(options, dict): return {'state':'not_configured', 'permission':'unknown'}
    try:
        host = absolute(options['hostExecutable'])
        if not host.is_file(): raise ValueError('notification host unavailable')
        if options.get('enabled') is not True:
            subprocess.run([str(host),'stop'], check=True, capture_output=True, timeout=8)
            return {'state':'disabled', 'permission':'unknown'}
        context_path = absolute(options['contextPath'])
        application = absolute(options['openAppPath'])
        if application.suffix != '.app' or not application.is_dir(): raise ValueError('app unavailable')
        response = subprocess.run([str(host),'status'], check=True, capture_output=True, text=True, timeout=8)
        permission = response.stdout.strip()
        if permission not in ('authorized','denied','not_determined','unknown'): permission='unknown'
        action = 'Откройте сохранённый контекст, если хотите продолжить.'
        try:
            with context_path.open('rb') as stream: data = stream.read(16385)
            if len(data) > 16384: raise ValueError('context too large')
            context = json.loads(data)
            remote = recovery_report.get('context') or {}
            note = context.get('note'); ref = context.get('taskRef')
            if isinstance(ref,str) and ref and ref == remote.get('taskRef') and isinstance(note,str) and note.strip() and len(note) <= 160:
                action = note.strip()
        except (OSError, ValueError, TypeError, AttributeError): pass
        def submit(message):
            payload = json.dumps({'body':message['body'], 'appPath':str(application), 'configPath':str(absolute(config_path)), 'outputPath':str(absolute(config['outputDirectory']))}).encode()
            process = subprocess.Popen([str(host),'post'],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
            process.stdin.write(payload); process.stdin.close()
        return evaluate(absolute(config['outputDirectory']), opted_in=True, permission=permission,
                        recovery=recovery_report, next_action=action, deliver=submit, now=now)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        return {'state':'unavailable', 'permission':'unknown'}


def collect_vault_sources(config, output):
    paths, health = [], {}
    roots = config.get('vaultTelemetryRoots',[])
    if not roots:return paths,health
    spec=importlib.util.spec_from_file_location('review_vault_events',SCRIPT.with_name('vault_events.py'))
    collector=importlib.util.module_from_spec(spec);spec.loader.exec_module(collector)
    for index,root in enumerate(roots):
        rows,state=collector.collect(absolute(root))
        cache=output/f'vault-android-{index}.jsonl'
        if state['state'] in ('missing','unreadable') and cache.is_file() and cache.stat().st_size>0:
            state['cachedPriorReceipt']=True
        else:
            collector.save(cache,''.join(json.dumps(row,separators=(',',':'))+'\n' for row in rows))
        if not cache.exists():collector.save(cache,'')
        health[f'vaultSync{index}']=state
        paths.append(str(cache))
    return paths,health


def freshness_status(report, config, now):
    from metrics import instant
    threshold = config['staleAfterHours']
    for source in report['health'].values():
        stamp = source.get('latestObservedAt')
        reported = source.get('latestFutureAt') or source.get('latestReportedAt') or stamp
        advance = (instant(reported)-now).total_seconds() if reported else 0
        raw_age = (now-instant(stamp)).total_seconds()/3600 if stamp else None
        skew = source.get('futureEventCount',0)>0 or advance>0 or (raw_age is not None and raw_age<0)
        source['ageHours'] = raw_age if raw_age is not None and raw_age>=0 else None
        source['clockSkewSeconds'] = max(0,advance)
        source['freshness'] = 'clock_skew' if skew else ('no_data' if raw_age is None else ('stale' if raw_age>threshold else 'recent'))
    stamp = report.get('latestObservedAt')
    age = (now-instant(stamp)).total_seconds()/3600 if stamp else None
    skew = report.get('clockDiagnostics',{}).get('futureEventCount',0)>0 or any(source['freshness']=='clock_skew' for source in report['health'].values()) or (age is not None and age<0)
    return {'ranAtUTC':now.isoformat(),'state':'clock_skew' if skew else ('no_data' if age is None else ('partial' if report.get('qaSeparation',{}).get('state') != 'observed' or any(state['state']!='observed' for state in report.get('vaultSyncHealth',{}).values()) else ('stale' if age>threshold else 'observed'))),
            'expectedSources':len(config['events'])+len(config.get('vaultTelemetryRoots',[]))+bool(config.get('movebreak')),
            'latestAgeHours':age if age is not None and age>=0 else None,'staleAfterHours':threshold,
            'sourcesNeedingAttention':sum(source['freshness']!='recent' for source in report['health'].values()),
            'sourceHealth':report['health'],'vaultSyncHealth':report.get('vaultSyncHealth',{}),'qaSeparation':report.get('qaSeparation',{'state':'unknown'}),
            'vaultSourcesNeedingAttention':sum(state['state']!='observed' for state in report.get('vaultSyncHealth',{}).values()),'externalMessagesSent':0}


def current_report(config, now=None):
    now = now or dt.datetime.now(dt.timezone.utc)
    timezone = config.get('timezone','Europe/Minsk')
    today = now.astimezone(ZoneInfo(timezone)).date()
    output = private_directory(config['outputDirectory'])
    destination = output/'current-day.json'
    vault_sources,vault_health=collect_vault_sources(config,output)
    command = [sys.executable,str(SCRIPT.with_name('metrics.py')),'cycle','--day',str(today),
               '--timezone',timezone,'--as-of',now.isoformat(),'--output',str(destination),'--qa-scopes',str(output/'qa-scopes.jsonl')]
    for path in config['events']+vault_sources:
        command.extend(['--events',str(absolute(path))])
    if config.get('movebreak'):
        command.extend(['--movebreak',str(absolute(config['movebreak'])),'--movebreak-category',config.get('movebreakCategory') or 'unknown'])
    subprocess.run(command,check=True,capture_output=True,text=True)
    report = json.loads(destination.read_text())
    report['vaultSyncHealth']=vault_health
    report.update({'generatedAtUTC':now.isoformat(),'period':'current_local_day_so_far','isPartialDay':True})
    report['dailyCheck'] = freshness_status(report,config,now)
    save_private(destination,report)
    return {'report':str(destination),'day':str(today),'isPartialDay':True}


def runtime_integrity(config):
    root = pathlib.Path(config['runtimeDirectory']) if config.get('runtimeDirectory') else None
    expected = config.get('runtimeHashes', {})
    changed = []
    for name, digest in expected.items():
        target = root/name
        if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            changed.append(name)
    return {'state':'verified' if expected and not changed else 'unknown_or_changed', 'changed':changed}



def protection_review(config, vault_sources, vault_health, start_day, end_day, now):
    """Observed events only; source completeness and enforcement remain unknown."""
    from metrics import instant, validate
    import uuid
    if config.get('protectionReviewEnabled') is not True:
        return {'state':'not_enabled', 'counts':None, 'reliefSeconds':None, 'policyVersion':'unknown'}
    zone=ZoneInfo(config.get('timezone','Europe/Minsk'))
    start=dt.datetime.combine(start_day,dt.time(),zone).astimezone(dt.timezone.utc)
    end=dt.datetime.combine(end_day,dt.time(),zone).astimezone(dt.timezone.utc)
    counts={'relief':0,'return':0,'explicitExit':0}
    times={'relief':[], 'return':[], 'explicitExit':[]}
    versions=set(); revisions=set(); source_states=[]; seen=set()
    mapping={'relief_started':'relief','returned':'return','window_exited':'explicitExit'}
    max_source_bytes=4*1024*1024  # Fail closed rather than silently summarise a truncated source.
    for value in config.get('windowEventPaths',[]):
        try:
            path=absolute(value)
            with path.open('rb') as stream: data=stream.read(max_source_bytes+1)
            if len(data)>max_source_bytes: raise ValueError('source too large')
            if data and not data.endswith(b'\n'): raise ValueError('partial tail')
            rows=[]
            for line in data.splitlines():
                row=json.loads(line)
                if not {'event','windowId','at'} <= set(row) or set(row)-{'event','windowId','at','policyVersion','eventId','policyRevisionId','commitStatus'}: raise ValueError('unknown fields')
                if 'eventId' in row: uuid.UUID(row['eventId'])
                if 'policyRevisionId' in row:
                    if not isinstance(row['policyRevisionId'],str): raise ValueError('invalid revision')
                    uuid.UUID(row['policyRevisionId'])
                if 'commitStatus' in row and row['commitStatus'] != 'committed': raise ValueError('unknown commit status')
                if row['event'] not in ('configured','window_started','relief_started','relief_ended','rest_started','returned','exit_requested','exit_cancelled','window_exited','window_expired'): raise ValueError('unknown event')
                uuid.UUID(row['windowId']); stamp=instant(row['at'])
                if 'policyVersion' in row and (type(row['policyVersion']) is not int or row['policyVersion'] != 2): raise ValueError('unknown policy version')
                if stamp > now: raise ValueError('future event')
                rows.append((row,stamp))
            source_states.append('observed' if rows else 'unknown')
            for row,stamp in rows:
                if start <= stamp < end:
                    if 'eventId' in row:
                        if row['eventId'] in seen: continue
                        seen.add(row['eventId'])
                    versions.add(row.get('policyVersion','unknown'))
                    if row.get('commitStatus') == 'committed' and row.get('policyRevisionId'):
                        revisions.add(str(uuid.UUID(row['policyRevisionId'])))
                    category=mapping.get(row['event'])
                    if category: counts[category]+=1; times[category].append(row['at'])
        except (OSError,ValueError,TypeError,KeyError,UnicodeError):
            source_states.append('unknown')
    android_map={'bounded_relief_started':'relief','rest_returned':'return','relief_ended_early':'return','protection_exit':'explicitExit'}
    for i,value in enumerate(vault_sources):
        health=vault_health.get(f'vaultSync{i}',{})
        if health.get('state') not in ('observed','partial'):
            source_states.append('unknown'); continue
        source_states.append(health['state'])
        try:
            with absolute(value).open('rb') as stream: data=stream.read(max_source_bytes+1)
            if len(data)>max_source_bytes: raise ValueError('source too large')
            data=data.decode('utf-8')
            rows=[]
            for line in data.splitlines():
                row=json.loads(line); validate(row); rows.append(row)
            for row in rows:
                if row['eventId'] in seen: continue
                seen.add(row['eventId']); stamp=instant(row['occurredAt'])
                if start <= stamp < end and stamp <= now:
                    category=android_map.get(row.get('reason'))
                    if category: counts[category]+=1; times[category].append(row['occurredAt'])
                    versions.add('unknown')
        except (OSError,ValueError,TypeError,KeyError,UnicodeError): source_states[-1]='unknown'
    available=any(state in ('observed','partial','empty') for state in source_states)
    return {'state':'observed_incomplete' if available else 'unknown', 'sourceStates':source_states,
            'counts':counts if available else None, 'eventTimesUTC':times if available else None,
            'reliefSeconds':None, 'policyVersion':'unknown', 'committedPolicyRevisionIds':sorted(revisions), 'revisionHistoryCompleteness':'unknown', 'policyContractVersions':sorted(versions,key=str) if versions else 'unknown',
            'meaning':'Return means explicit return to the protection policy, not resumed work. Observed events only; missing events, current selected policy and elapsed relief are unknown. Committed revision IDs reflect local snapshot saves, not enforcement; history may be incomplete. Contract version is not a policy revision. No claim of enforcement or complete capture.'}

def weekly_review(output, today, protocol, now=None, config=None, vault_sources=None, vault_health=None):
    from metrics import instant
    now = now or dt.datetime.now(dt.timezone.utc)
    days = [today-dt.timedelta(days=i) for i in range(7,0,-1)]
    reports, invalid_reports = [], []
    for day in days:
        path = output/f'{day}.json'
        if path.exists():
            try:
                report = json.loads(path.read_text())
                if not isinstance(report.get('clockDiagnostics'),dict) or not report.get('evaluatedAtUTC') or instant(report['evaluatedAtUTC'])>now:
                    raise ValueError('legacy or future-evaluated report cannot establish safe measurements')
                for category in ('work','study'):
                    for evidence in ('timer','activity','self_report','verified','unknown'):
                        value = report['categories'][category]['focusHoursByEvidence'][evidence]
                        if value is not None and (type(value) not in (int,float) or value < 0):
                            raise ValueError('invalid metric')
                reports.append(report)
            except (ValueError,OSError,KeyError,TypeError):
                invalid_reports.append(str(day))
    known_days = sum(any(value is not None for category in report['categories'].values()
                         for value in category['focusHoursByEvidence'].values()) for report in reports)
    result = {'schemaVersion':1,'clockPolicyVersion':1,'evaluatedAtUTC':now.isoformat(),'periodStart':str(days[0]),'periodEndExclusive':str(today),
              'invalidReportDays':invalid_reports, 'quarantinedFutureRecords':sum(r.get('clockDiagnostics',{}).get('quarantinedFutureRecords',0) for r in reports),
              'clockMeaning':'Future records excluded from primary measurements; legacy reports without clock checks are unknown.', 'reportsPresent':len(reports),'daysWithMeasuredFocus':known_days,
              'measurementCoverageFraction':known_days/7,
              'coverageMeaning':'Days with any measured focus; not within-day observation coverage.',
              'withinDayCompleteness':'unknown', 'nextReviewDate':str(today+dt.timedelta(days=7)),
              'adaptation':'none', 'adaptationReason':'No approved, validated policy adaptation rules; missing observations are not zero work.',
              'focusHours':{category:{e:sum(values) if values else None
                                    for e in ('timer','activity','self_report','verified','unknown')
                                    for values in [[r['categories'][category]['focusHoursByEvidence'][e] for r in reports
                                                    if r['categories'][category]['focusHoursByEvidence'][e] is not None]]}
                            for category in ('work','study')},
              'protocolReview':protocol,'decision':'insufficient_for_automatic_benefit_claim',
              'nextAction':'Review recorded outcomes, missing coverage, maintenance burden and protocol criteria locally.',
              'externalMessagesSent':0}
    result['protectionReview'] = protection_review(config or {}, vault_sources or [], vault_health or {}, days[0], today, now)
    save_private(output/f'weekly-{today}.json', result)
    save_private(output/'weekly-latest.json', result)
    return result


def service_paths(directory):
    directory = absolute(directory)
    return directory, pathlib.Path.home()/'Library/LaunchAgents'/f'{LABEL}.plist'


def service_status(directory):
    directory, installed = service_paths(directory)
    result = subprocess.run(['/bin/launchctl','print',f'gui/{os.getuid()}/{LABEL}'], capture_output=True,text=True)
    return {'installed':installed.exists(),'loaded':result.returncode == 0,
            'localReport':str(directory/'reports/latest.json'),
            'reportExists':(directory/'reports/latest.json').exists(),
            'launchctl':result.stdout if result.returncode == 0 else result.stderr}


def service_install(directory):
    directory, installed = service_paths(directory)
    config_path = directory/'review-config.json'
    config = json.loads(config_path.read_text())
    if runtime_integrity(config)['state'] != 'verified':
        raise ValueError('runtime hashes must verify before installation')
    existing = None
    if installed.exists():
        with installed.open('rb') as stream:
            existing = plistlib.load(stream)
        if existing.get('Label') != LABEL or str(directory/'runtime/scheduler.py') not in existing.get('ProgramArguments',[]):
            raise ValueError('existing job does not belong to this private directory')
    config['enabledByUser'] = True
    save_private(config_path,config)
    installed.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(directory/'local-review.plist',installed)
    os.chmod(installed,0o600)
    if existing:
        subprocess.run(['/bin/launchctl','bootout',f'gui/{os.getuid()}/{LABEL}'],capture_output=True,text=True)
    subprocess.run(['/bin/launchctl','bootstrap',f'gui/{os.getuid()}',str(installed)],check=True,capture_output=True,text=True)
    subprocess.run(['/bin/launchctl','kickstart','-k',f'gui/{os.getuid()}/{LABEL}'],check=True,capture_output=True,text=True)
    return service_status(directory)


def private_bytes(path, content):
    fd, temporary = tempfile.mkstemp(dir=path.parent,prefix='.review-upgrade-')
    try:
        with os.fdopen(fd,'wb') as stream: stream.write(content)
        os.replace(temporary,path)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


def service_upgrade(directory):
    directory, installed = service_paths(directory)
    config_path = directory/'review-config.json'
    config = json.loads(config_path.read_text())
    runtime = directory/'runtime'
    if absolute(config['runtimeDirectory']) != runtime or not installed.exists():
        raise ValueError('upgrade requires the owned installed runtime')
    with installed.open('rb') as stream: job = plistlib.load(stream)
    if job.get('Label') != LABEL or str(runtime/'scheduler.py') not in job.get('ProgramArguments',[]):
        raise ValueError('refusing to upgrade an unrelated installed job')
    # Prepare everything before stopping the existing owned service.
    stage = pathlib.Path(tempfile.mkdtemp(dir=directory,prefix='.runtime-stage-'))
    hashes = {}
    try:
        for name in ('scheduler.py','metrics.py','vault_events.py','recovery.py','recovery_reminder.py'):
            content = SCRIPT.with_name(name).read_bytes()
            private_bytes(stage/name,content)
            hashes[name] = hashlib.sha256(content).hexdigest()
        shared = SCRIPT.parent.parent/'shared'
        if not shared.exists(): shared = SCRIPT.parent/'shared'
        if shared.exists():
            (stage/'shared').mkdir(mode=0o700)
            for name in ('validate_events.py','event-v1.schema.json'):
                content = (shared/name).read_bytes()
                private_bytes(stage/'shared'/name,content)
                hashes['shared/'+name] = hashlib.sha256(content).hexdigest()
        save_private(stage/'manifest.json',hashes)
        candidate = dict(config,runtimeDirectory=str(stage),runtimeHashes=hashes)
        if runtime_integrity(candidate)['state'] != 'verified': raise ValueError('staged runtime invalid')
        backups = directory/'.upgrades'
        backups.mkdir(mode=0o700,exist_ok=True)
        backup = pathlib.Path(tempfile.mkdtemp(dir=backups,prefix='upgrade-'))
        private_bytes(backup/'review-config.json',config_path.read_bytes())
        private_bytes(backup/'installed.plist',installed.read_bytes())
        private_bytes(backup/'local-review.plist',(directory/'local-review.plist').read_bytes())
        loaded = subprocess.run(['/bin/launchctl','print',f'gui/{os.getuid()}/{LABEL}'],capture_output=True,text=True).returncode == 0
        if loaded:
            subprocess.run(['/bin/launchctl','bootout',f'gui/{os.getuid()}/{LABEL}'],check=True,capture_output=True,text=True)
        switched = False
        try:
            runtime.rename(backup/'runtime')
            stage.rename(runtime)
            switched = True
            config['runtimeHashes'] = hashes
            config.setdefault('currentDayRefreshSeconds',300)
            save_private(config_path,config)
            job['StartInterval'] = config['currentDayRefreshSeconds']
            job['RunAtLoad'] = True
            content = plistlib.dumps(job)
            private_bytes(directory/'local-review.plist',content)
            private_bytes(installed,content)
            if loaded:
                subprocess.run(['/bin/launchctl','bootstrap',f'gui/{os.getuid()}',str(installed)],check=True,capture_output=True,text=True)
            return {'upgraded':True,'loadedBeforeUpgrade':loaded,'backup':str(backup),
                    'sourcesPreserved':True,'reportsPreserved':True,'runtimeIntegrity':runtime_integrity(config),
                    'currentDayReport':str(pathlib.Path(config['outputDirectory'])/'current-day.json')}
        except Exception as error:
            subprocess.run(['/bin/launchctl','bootout',f'gui/{os.getuid()}/{LABEL}'],capture_output=True,text=True)
            if switched:
                runtime.rename(backup/'failed-runtime')
            if (backup/'runtime').exists(): (backup/'runtime').rename(runtime)
            private_bytes(config_path,(backup/'review-config.json').read_bytes())
            private_bytes(installed,(backup/'installed.plist').read_bytes())
            private_bytes(directory/'local-review.plist',(backup/'local-review.plist').read_bytes())
            if loaded:
                subprocess.run(['/bin/launchctl','bootstrap',f'gui/{os.getuid()}',str(installed)],capture_output=True,text=True)
            raise ValueError('upgrade failed; prior files restored; check service status') from error
    finally:
        if stage.exists(): shutil.rmtree(stage)


def service_uninstall(directory):
    directory, installed = service_paths(directory)
    if installed.exists():
        with installed.open('rb') as stream:
            job = plistlib.load(stream)
        if job.get('Label') != LABEL or str(directory/'runtime/scheduler.py') not in job.get('ProgramArguments',[]):
            raise ValueError('refusing to remove an unrelated installed job')
        subprocess.run(['/bin/launchctl','bootout',f'gui/{os.getuid()}/{LABEL}'],capture_output=True,text=True)
        installed.unlink()
    config_path = directory/'review-config.json'
    config = json.loads(config_path.read_text())
    config['enabledByUser'] = False
    save_private(config_path,config)
    return {'installed':False,'enabled':False,'dataRetained':str(directory),'externalMessagesSent':0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    generate = commands.add_parser('prepare')
    generate.add_argument('--directory', required=True)
    generate.add_argument('--events', action='append', default=[])
    generate.add_argument('--timezone', default='Europe/Minsk')
    generate.add_argument('--protocol')
    generate.add_argument('--vault-telemetry-root',action='append',default=[])
    generate.add_argument('--movebreak')
    generate.add_argument('--movebreak-category', choices=['work','study','unknown'],default='unknown')
    for name in ('install','uninstall','status','upgrade'):
        service = commands.add_parser(name)
        service.add_argument('--directory',required=True)
    for name in ('run','current'):
        runner = commands.add_parser(name)
        runner.add_argument('--config', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            result = prepare(args.directory,args.events,args.timezone,args.protocol,movebreak=args.movebreak,movebreak_category=args.movebreak_category,vault_roots=args.vault_telemetry_root)
        elif args.command == 'run':
            result = run(args.config)
        elif args.command == 'current':
            os.umask(0o077)
            config = json.loads(absolute(args.config).read_text())
            result = current_report(config)
        else:
            result = {'install':service_install,'uninstall':service_uninstall,'status':service_status,'upgrade':service_upgrade}[args.command](args.directory)
        print(json.dumps(result, indent=2))
    except (ValueError, OSError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, f'{error}\n')


if __name__ == '__main__':
    main()
