#!/usr/bin/env python3
"""Local, explicit-input review. Never reads journals or sends notifications."""
import argparse
import datetime as dt
import json
import importlib.util
import math
import pathlib
import uuid
from zoneinfo import ZoneInfo

UTC = dt.timezone.utc
EVIDENCE = {'timer', 'activity', 'self_report', 'verified', 'unknown'}


def shared_validator():
    root = pathlib.Path(__file__).resolve().parent
    candidates = (root.parent/'shared/validate_events.py', root/'shared/validate_events.py')
    path = next((path for path in candidates if path.is_file()), None)
    if path is None:
        raise ValueError('shared contract validator unavailable')
    spec = importlib.util.spec_from_file_location('metrics_shared_validator',path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.validate


SHARED_VALIDATE = shared_validator()


def instant(value):
    if not isinstance(value, str):
        raise ValueError('timestamp must explicitly use UTC')
    result = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.tzinfo is None or result.utcoffset() != dt.timedelta(0):
        raise ValueError('timestamp must explicitly use UTC')
    return result


def day_window(day, timezone='Europe/Minsk'):
    zone = ZoneInfo(timezone)
    date = dt.date.fromisoformat(day)
    start = dt.datetime.combine(date, dt.time(), zone).astimezone(UTC)
    end = dt.datetime.combine(date + dt.timedelta(days=1), dt.time(), zone).astimezone(UTC)
    return start, end


def read_lines(path):
    try:
        lines = path.read_text().splitlines()
    except FileNotFoundError:
        return [], {'state': 'missing', 'valid': 0, 'invalid': 0}
    except PermissionError:
        return [], {'state': 'unreadable', 'reason': 'permission_denied', 'valid': 0, 'invalid': 0}
    except UnicodeError:
        return [], {'state': 'unreadable', 'reason': 'invalid_encoding', 'valid': 0, 'invalid': 0}
    except OSError:
        return [], {'state': 'unreadable', 'reason': 'io_error', 'valid': 0, 'invalid': 0}
    rows, invalid = [], 0
    for line in lines:
        if not line.strip():
            continue
        try:
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError('object required')
            rows.append(row)
        except (ValueError, TypeError):
            invalid += 1
    return rows, {'state': 'present', 'valid': len(rows), 'invalid': invalid}


def validate(row):
    SHARED_VALIDATE(row)
    required = {'schemaVersion','eventId','sessionId','occurredAt','source','category','type','evidence','metrics'}
    if not required.issubset(row) or not isinstance(row['metrics'], dict):
        raise ValueError('required fields')
    if type(row.get('schemaVersion')) is not int or row.get('schemaVersion') != 1 or row.get('category') not in ('work', 'study', None):
        raise ValueError('schema/category')
    if not isinstance(row['eventId'], str):
        raise ValueError('eventId')
    uuid.UUID(row['eventId'])
    if row.get('sessionId') is not None:
        if not isinstance(row['sessionId'], str):
            raise ValueError('sessionId')
        uuid.UUID(row['sessionId'])
    if row.get('coverage') not in (None,'complete','partial','missing'):
        raise ValueError('coverage')
    for key in ('outcome','taskRef','deviceId','reason'):
        if row.get(key) is not None and not isinstance(row[key], str):
            raise ValueError(key)
    if row.get('type') not in ('started','paused','resumed','completed','stopped','missed','disabled','uninstalled','heartbeat','health','maintenance'):
        raise ValueError('type')
    instant(row['occurredAt'])
    if row.get('source') not in ('mac', 'android', 'movebreak'):
        raise ValueError('source')
    if row.get('evidence', 'unknown') not in EVIDENCE:
        raise ValueError('evidence')
    if set(row['metrics']) - {'focusSeconds','distractionSeconds','maintenanceSeconds'}:
        raise ValueError('unknown metric')
    for value in row['metrics'].values():
        if value is not None and (isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or value < 0):
            raise ValueError('metric must be nonnegative number or null')
    if row.get('startedAt') or row.get('endedAt'):
        if instant(row['endedAt']) < instant(row['startedAt']):
            raise ValueError('interval')
        measured = row['metrics'].get('focusSeconds')
        if measured is not None and abs((instant(row['endedAt'])-instant(row['startedAt'])).total_seconds()-measured) > (0 if measured == 0 else 1):
            raise ValueError('focus interval must exclude pauses and match measured duration')
    return row


def movebreak(rows, category):
    """Only completed blocks with measured duration; never infer planned minutes."""
    converted, ignored = [], 0
    for row in rows:
        try:
            if row.get('type') != 'block_completed':
                ignored += 1
                continue
            seconds = row.get('focusSeconds', row.get('actualSeconds'))
            if isinstance(seconds, bool) or not isinstance(seconds, (int, float)) or not math.isfinite(seconds) or seconds < 0:
                raise ValueError('missing measured duration')
            end = instant(row.get('at') or row.get('createdAt'))
            identity = json.dumps(row, sort_keys=True, separators=(',', ':'))
            converted.append({'schemaVersion': 1, 'eventId': str(uuid.uuid5(uuid.NAMESPACE_URL, identity)), 'sessionId': None,
                              'category': category, 'occurredAt': end.isoformat().replace('+00:00', 'Z'),
                              'source': 'movebreak', 'evidence': 'timer', 'type': 'completed',
                              'startedAt': (end-dt.timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z'),
                              'endedAt': end.isoformat().replace('+00:00', 'Z'),
                              'metrics': {'focusSeconds': seconds}, 'intervalEstimated': True})
        except (ValueError, TypeError, OverflowError):
            ignored += 1
    return converted, ignored


def separate_qa(rows, path, now):
    """Drop whole overlapping events, never guess interval boundaries or historical coverage."""
    try:
        data=path.read_bytes()
        if not data or not data.endswith(b'\n') or len(data)>4*1024*1024: raise ValueError('missing or incomplete metadata')
        entries=[json.loads(line) for line in data.splitlines()]
        coverage=instant(entries[0]['coverageFrom']); opened={}; periods=[]
        if coverage>now: raise ValueError('future coverage')
        for row in entries:
            if row.get('schemaVersion')!=1 or row.get('event') not in ('start','end'): raise ValueError('invalid scope')
            ident=str(uuid.UUID(row['scopeId'])); stamp=instant(row['at'])
            if stamp>now: raise ValueError('future scope')
            if row['event']=='start':
                if ident in opened or any(x[2]==ident for x in periods): raise ValueError('duplicate scope')
                opened[ident]=stamp
            else:
                if ident not in opened or stamp<opened[ident]: raise ValueError('invalid close')
                periods.append((opened.pop(ident),stamp,ident))
        periods.extend((at,now,ident) for ident,at in opened.items())
        state='incomplete_open_scope' if opened else 'observed'
    except (OSError,ValueError,KeyError,TypeError,UnicodeError):
        # If metadata is unavailable do not claim personal passive context.
        filtered=[r for r in rows if not isinstance(r.get('context'),dict)]
        return filtered, {'state':'metadata_unknown','excludedEvents':len(rows)-len(filtered),'openScopes':None,'meaning':'QA separation unavailable; passive context omitted, historical coverage unknown.'}
    filtered=[]; excluded=0
    for row in rows:
        context=row.get('context')
        try:
            a=instant(row.get('startedAt') or row['occurredAt']); b=instant(row.get('endedAt') or row['occurredAt'])
            point=a==b
            before_coverage=isinstance(context,dict) and a<coverage
            overlap=any((x<=a<y if point else a<y and b>x) for x,y,_ in periods)
            bundle=context.get('bundleId','') if isinstance(context,dict) else ''
            known_fixture=isinstance(bundle,str) and ('fixture' in bundle.lower() or bundle.lower().endswith('.qa'))
            if before_coverage or overlap or known_fixture: excluded+=1; continue
            filtered.append(row)
        except (ValueError,KeyError,TypeError): excluded+=1
    return filtered, {'state':state,'excludedEvents':excluded,'openScopes':len(opened),'coverageFromUTC':coverage.isoformat(),
                      'meaning':'Only explicitly scoped QA and identifiable fixtures are omitted; unmarked QA is unknown. Pre-coverage context omitted. Open scopes remain unknown until explicitly closed.'}


def context_summary(rows, start, end):
    intervals, gaps, discarded = [], [], 0
    for row in rows:
        context = row.get('context')
        if not isinstance(context, dict):
            continue
        seconds = context.get('observedSeconds')
        gap = context.get('unknownGapSeconds')
        if start <= instant(row['occurredAt']) < end and type(gap) in (int,float) and math.isfinite(gap) and gap >= 0:
            gaps.append((row['source'],gap))
        if seconds is None:
            continue
        if type(seconds) not in (int,float) or not math.isfinite(seconds) or seconds < 0 or not row.get('startedAt') or not row.get('endedAt'):
            discarded += 1
            continue
        a, b = instant(row['startedAt']), instant(row['endedAt'])
        if b < a or abs((b-a).total_seconds()-seconds) > 3:
            discarded += 1
            continue
        a,b = max(start,a),min(end,b)
        if a < b:
            intervals.append((a,b,row['category'] or 'unknown',row['source']))
    def union(source=None):
        chosen = [item for item in intervals if source is None or item[3] == source]
        totals = {category:None for category in ('work','study','unknown')}
        points = sorted({point for a,b,_,_ in chosen for point in (a,b)})
        for a,b in zip(points,points[1:]):
            categories = {c for x,y,c,_ in chosen if x <= a and y >= b}
            if categories:
                category = next(iter(categories)) if len(categories) == 1 else 'unknown'
                totals[category] = (totals[category] or 0)+(b-a).total_seconds()
        return totals
    totals = union()
    return {'secondsByCategory':totals,
            'secondsBySource':{source:union(source) for source in ('mac','android','movebreak')},
            'observedUnionSeconds':nullable_sum(list(totals.values())),
            'unknownGapSecondsReported':nullable_sum([gap for _,gap in gaps]),
            'unknownGapSecondsBySource':{source:nullable_sum([gap for s,gap in gaps if s == source]) for source in ('mac','android','movebreak')},
            'unknownGapEvents':len(gaps),'discardedContextRecords':discarded,
            'readingAssessment':'unknown',
            'meaning':'Foreground API context only; screen availability, reading, attention and useful work are not inferred.',
            'coverageDenominator':None,
            'gapMeaning':'Reported unknown gaps may overlap sources; not time proved idle or unproductive.'}


def future_event(row, now):
    return any(row.get(key) and instant(row[key]) > now for key in ('occurredAt','startedAt','endedAt'))


def source_clock_health(rows, now):
    future = [row for row in rows if future_event(row,now)]
    safe = [row for row in rows if not future_event(row,now)]
    latest = max((instant(row['occurredAt']) for row in safe),default=None)
    latest_reported = max((instant(row['occurredAt']) for row in rows),default=None)
    latest_future = max((instant(row[key]) for row in future for key in ('occurredAt','startedAt','endedAt') if row.get(key) and instant(row[key])>now),default=None)
    return {'latestObservedAt':latest.isoformat() if latest else None,
            'latestReportedAt':latest_reported.isoformat() if latest_reported else None,
            'latestFutureAt':latest_future.isoformat() if latest_future else None,
            'futureEventCount':len(future),'clockState':'clock_skew' if future else 'no_future_observed'}


def review(rows, day, health, timezone='Europe/Minsk', now=None):
    start, end = day_window(day, timezone)
    now = now or dt.datetime.now(UTC)
    unique, seen, conflicts = [], {}, set()
    for row in rows:
        key = row['eventId']
        if key in seen and seen[key] != row:
            conflicts.add(key)
        else:
            seen[key] = row
    # Quarantine every version of a conflicting id, rather than trust input order.
    unique = [row for key, row in seen.items() if key not in conflicts]
    clock_health = source_clock_health(unique,now)
    quarantined = [row for row in unique if future_event(row,now)]
    unique = [row for row in unique if not future_event(row,now)]
    daily = [r for r in unique if start <= instant(r['occurredAt']) < end]
    intervals = []
    for r in unique:
        if not r.get('intervalEstimated') and r.get('startedAt') and r.get('endedAt') and (r.get('metrics') or {}).get('focusSeconds') is not None:
            a, b = max(start, instant(r['startedAt'])), min(end, instant(r['endedAt']))
            if a < b:
                intervals.append((a, b, r))
    totals = {c: {e: None for e in EVIDENCE} for c in ('work', 'study')}
    for r in daily:
        if r['category'] in totals and r['metrics'].get('focusSeconds') == 0:
            totals[r['category']][r['evidence']] = 0
    points = sorted({p for a,b,_ in intervals for p in (a,b)})
    ambiguous = 0
    evidence_rank = ['verified', 'self_report', 'timer', 'activity', 'unknown']
    for a,b in zip(points, points[1:]):
        active = [r for x,y,r in intervals if x <= a and y >= b]
        if not active:
            continue
        seconds = (b-a).total_seconds()
        categories = {r['category'] for r in active}
        if len(categories) != 1 or None in categories:
            ambiguous += seconds
            continue
        category = next(iter(categories))
        evidence = next(e for e in evidence_rank if any(r.get('evidence', 'unknown') == e for r in active))
        totals[category][evidence] = (totals[category][evidence] or 0) + seconds
    summary = {}
    for category in ('work', 'study'):
        records = [r for r in daily if r['category'] == category]
        summary[category] = {'focusSecondsByEvidence': totals[category],
                            'focusHoursByEvidence': {k: None if v is None else round(v/3600, 4) for k,v in totals[category].items()},
                            'estimatedFocusSecondsByEvidence': {e: nullable_sum([r['metrics'].get('focusSeconds') for r in records if r.get('intervalEstimated') and r['evidence'] == e]) for e in EVIDENCE},
                            'reportedMetrics': {k: nullable_sum([(r.get('metrics') or {}).get(k) for r in records]) for k in ('distractionSeconds','maintenanceSeconds')},
                            'sessionsObserved': len({r['sessionId'] for r in records if r.get('sessionId')}),
                            'statusEvents': {s: sum(r.get('type') == s for r in records) for s in ('started','completed','stopped','missed','disabled','uninstalled')},
                            'unpositionedFocusRecords': sum((r.get('metrics') or {}).get('focusSeconds') is not None and not r.get('startedAt') for r in records)}
    latest = max((instant(r['occurredAt']) for r in unique), default=None)
    observed = [r for r in unique if instant(r['occurredAt']) <= min(now, end)]
    recent = sorted(observed, key=lambda r: instant(r['occurredAt']))
    # Session state and app reachability are different signals. A heartbeat does
    # not restart a stopped session, and a later start supersedes an old stop.
    lifecycle = {'started','resumed','paused','completed','stopped','missed','disabled','uninstalled'}
    latest_state = next((r for r in reversed(recent) if r['type'] in lifecycle), None)
    session_state = latest_state['type'] if latest_state else 'unknown'
    observation = 'unknown' if not recent else ('stale' if (now-instant(recent[-1]['occurredAt'])).total_seconds() > 86400 else 'recent')
    recovery_state = session_state if latest_state and start <= instant(latest_state['occurredAt']) < end else ('unknown' if not daily else 'observed')
    return {'schema': 1, 'evaluatedAtUTC':now.isoformat(), 'day': day, 'timezone': timezone, 'windowStartUTC': start.isoformat(), 'windowEndUTC': end.isoformat(), 'categories': summary, 'ambiguousFocusSeconds': ambiguous,
            'clockDiagnostics':dict(clock_health,quarantinedFutureRecords=len(quarantined),
                                    meaning='Future-dated records retained in source but excluded from measurements; no zero work inferred.'),
            'contextObservation': context_summary(unique,start,end), 'health': health, 'conflictingEventIds': sorted(conflicts), 'rejectedConflictingRecords': sum(r['eventId'] in conflicts for r in rows), 'coverageEvents': {c: sum(r.get('coverage') == c for r in daily) for c in ('complete','partial','missing')}, 'uncategorizedEstimatedFocusSeconds': nullable_sum([r['metrics'].get('focusSeconds') for r in daily if r['category'] is None and r.get('intervalEstimated')]), 'unknownCategoryEvents': sum(r['category'] is None for r in daily),
            'outcomes': [{'category': r['category'], 'evidence': r['evidence'], 'outcome': r['outcome']} for r in daily if r.get('outcome')], 'latestObservedAt': latest.isoformat() if latest else None,
            'recovery': {'state': recovery_state, 'lastKnownSessionState': session_state, 'observation': observation,
                         'currentSessionState': 'unknown', 'currentStateReason': 'Event history records last known state; no live session snapshot supplied.',
                         'nextAction': 'Resume one chosen task; no backlog report required.',
                         'automaticReturnAfterFullDeletion': False,
                         'prerequisite': 'User-chosen independent channel, consent, stop conditions, retention and restore procedure must be configured before an experiment.'},
            'limitations': ['Focus is an evidence-labelled interval proxy, not useful output.',
                            'Idle reading is not classified as distraction.',
                            'Missing heartbeat does not prove uninstall or non-use.',
                            'Missing telemetry is null, never zero work.',
                            'Distraction and maintenance are reported values; cross-device sums may overlap.']}


def nullable_sum(values):
    known = [v for v in values if v is not None]
    return sum(known) if known else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    cycle = sub.add_parser('cycle')
    cycle.add_argument('--events', type=pathlib.Path, action='append', default=[])
    cycle.add_argument('--movebreak', type=pathlib.Path)
    cycle.add_argument('--movebreak-category', choices=['work','study','unknown'], default='unknown')
    cycle.add_argument('--day', required=True, help='Calendar date YYYY-MM-DD in --timezone')
    cycle.add_argument('--timezone', default='Europe/Minsk')
    cycle.add_argument('--as-of', help='Explicit UTC evaluation instant; default current clock')
    cycle.add_argument('--output', type=pathlib.Path, required=True)
    cycle.add_argument('--qa-scopes', type=pathlib.Path)
    protocol = sub.add_parser('protocol')
    protocol.add_argument('--timezone', default='Europe/Minsk')
    protocol.add_argument('--start', required=True)
    protocol.add_argument('--end', required=True)
    protocol.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.command == 'protocol':
        start, end = dt.date.fromisoformat(args.start), dt.date.fromisoformat(args.end)
        if end <= start or start <= dt.datetime.now(ZoneInfo(args.timezone)).date():
            parser.error('choose explicit future start and later end')
        result = {'schema':1, 'timezone':args.timezone, 'startDate':str(start), 'endDate':str(end), 'activation':'blocked_until_prerequisites_complete',
                  'hypothesis':None, 'baselineDates':None, 'primaryOutcome':'focusHoursByEvidence, work and study separately; verified outcomes reviewed separately',
                  'continueCriteria':None, 'changeCriteria':None, 'stopCriteria':None, 'maintenanceBudgetMinutes':None,
                  'independentReturnChannel':None, 'reminderConsentAndStopRules':None, 'retentionAndRestore':None,
                  'note':'Dates are user-selected experiment boundaries, not an evidence-based universal dose.'}
    else:
        rows, health = [], {}
        for index, path in enumerate(args.events):
            source, state = read_lines(path)
            accepted = []
            for row in source:
                try:
                    accepted.append(validate(row))
                except (ValueError, KeyError, TypeError):
                    state['invalid'] += 1
            state['valid'] = len(accepted)
            state.update(source_clock_health(accepted, instant(args.as_of) if args.as_of else dt.datetime.now(UTC)))
            health[f'events{index}'] = state
            rows.extend(accepted)
        if args.movebreak:
            source, state = read_lines(args.movebreak)
            accepted, ignored = movebreak(source, None if args.movebreak_category == 'unknown' else args.movebreak_category)
            state.update({'accepted':len(accepted), 'ignoredOrUnsupported':ignored,
                          **source_clock_health(accepted,instant(args.as_of) if args.as_of else dt.datetime.now(UTC))})
            health['movebreak'] = state
            rows.extend(accepted)
        review_now=instant(args.as_of) if args.as_of else dt.datetime.now(UTC)
        if args.qa_scopes: rows, qa_health=separate_qa(rows,args.qa_scopes,review_now)
        else: qa_health={'state':'not_configured','meaning':'QA scope metadata not supplied; separation unknown.'}
        result = review(rows, args.day, health, args.timezone, now=review_now)
        result['qaSeparation']=qa_health
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False)+'\n')
    print(str(args.output))


if __name__ == '__main__':
    main()
