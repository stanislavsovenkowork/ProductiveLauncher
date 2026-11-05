#!/usr/bin/env python3
"""Review/install-idle/run/recover/remove only the V2 selected-app candidate.

No launchd, hosts, DNS, network, other guards, or personal applications are
modified by installation. The candidate remains unverified for real-world use.
"""
import fcntl
import ctypes
import datetime
import tempfile
from zoneinfo import ZoneInfo
import hashlib
import json
import os
import pathlib
import plistlib
import re
import socket
import stat
import subprocess
import sys
import uuid

ROOT = pathlib.Path('/Library/Application Support/FocusDeckGuardV2Candidate')
SOCKET = pathlib.Path('/var/run/dev.stanislav.focusdeck.guard-v2-candidate.sock')
ROOT_OWNER = 0
DAEMON = 'FocusDeckV2ProductionCandidate'
RECOVERY = 'FocusDeckV2CandidateRecover'
POLICY = 'window-v2-candidate.json'
LOG = 'candidate-events.jsonl'
FIXED_FILES = {'root-config.json', 'manifest.json', 'ready.json', DAEMON, RECOVERY, 'daemon.lock', POLICY, LOG}
PROTECTED_PREFIXES = ('hossin.asaadi.v2box', 'com.openai.', 'com.v2box.', 'com.hossinasaadi.v2box', 'net.openvpn.', 'org.openvpn.', 'com.tunnelblick.', 'net.tunnelblick.', 'com.apple.')
PROTECTED_WORDS = ('vpn', 'tunnel', 'wireguard', 'tailscale', 'zerotier', 'globalprotect', 'fortinet', 'cisco', 'gitlab', 'codex')
PROTECTED_DOMAINS = ['gitlab.eskhata.com', 'openai.com', 'chatgpt.com']


def fail(message):
    raise ValueError(message)


def valid_id(value):
    return isinstance(value, str) and len(value) <= 255 and '.' in value and re.fullmatch(r'[A-Za-z0-9_.-]+', value) is not None


def protected(value):
    text = value.lower()
    return text.startswith(PROTECTED_PREFIXES) or any(word in text for word in PROTECTED_WORDS)


def read_regular(path, owner=None, private=False, limit=20 * 1024 * 1024):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size > limit or (owner is not None and info.st_uid != owner) or (private and info.st_mode & 0o077):
            fail('Unexpected file type, owner, permissions or size.')
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            return stream.read()
    finally:
        os.close(fd)


def inspect_app(path):
    app = pathlib.Path(path)
    if not app.is_absolute() or app.suffix.lower() != '.app' or not app.is_dir(): fail('Expected an absolute .app path.')
    subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(app)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    detail = subprocess.run(['/usr/bin/codesign', '-dv', '--verbose=4', str(app)], check=True, text=True, capture_output=True).stderr
    requirement = subprocess.run(['/usr/bin/codesign', '-dr', '-', str(app)], check=True, text=True, capture_output=True).stderr
    def field(name, pattern):
        match = re.search(r'^' + name + '=' + pattern + r'$', detail, re.MULTILINE)
        return match.group(1) if match else None
    ident = field('Identifier', r'([^\r\n]+)')
    team = field('TeamIdentifier', r'([A-Z0-9]{10})')
    cdhash = field('CDHash', r'([0-9a-f]{40})')
    designated = re.search(r'^(?:# )?designated => (.+)$', requirement, re.MULTILINE)
    with (app/'Contents/Info.plist').open('rb') as f: bundle = plistlib.load(f).get('CFBundleIdentifier')
    return {'signingID': ident, 'teamID': team, 'requirement': designated.group(1) if designated else None, 'cdhash': cdhash, 'bundleID': bundle}


def draft(proposal_path, app_path, daemon_path, recovery_path, proposal_sha, daemon_sha, recovery_sha, uid):
    if any(not re.fullmatch(r'[0-9a-f]{64}', value) for value in (proposal_sha, daemon_sha, recovery_sha)): fail('Expected explicit reviewed SHA-256 values.')
    proposal_data = read_regular(proposal_path, owner=uid, private=True, limit=128 * 1024)
    if hashlib.sha256(proposal_data).hexdigest() != proposal_sha: fail('Proposal changed since review; nothing changed.')
    proposal = json.loads(proposal_data)
    if proposal.get('schemaVersion') != 1 or proposal.get('proposalStatus') != 'local_uninstalled' or proposal.get('allowedUID') != uid or uid < 500:
        fail('Proposal ownership, status or UID is invalid.')
    try: uuid.UUID(proposal['policyRevisionId'])
    except (KeyError, TypeError, ValueError): fail('Proposal has no valid policy revision.')
    ids, apps, requirements = proposal.get('blockedSigningIDs'), proposal.get('targetApplications'), proposal.get('signingRequirements')
    if not isinstance(ids, list) or not 1 <= len(ids) <= 64 or any(not valid_id(x) or protected(x) for x in ids) or len(set(ids)) != len(ids):
        fail('Selected IDs are missing, duplicated, malformed, or protected.')
    if proposal.get('blockedDomains') != [] or proposal.get('protectedDomains') != PROTECTED_DOMAINS:
        fail('Unexpected domain configuration.')
    if not isinstance(apps, list) or not isinstance(requirements, list) or len(apps) != len(ids) or len(requirements) != len(ids):
        fail('Each selected ID requires exactly one application and signing requirement.')
    if not all(isinstance(x, dict) for x in apps + requirements) or [x.get('signingID') for x in apps] != ids or [x.get('signingID') for x in requirements] != ids:
        fail('Application/requirement order or selected IDs do not match.')
    window = proposal.get('approvedWindow')
    if not isinstance(window, dict) or window.get('policyVersion') != 2 or window.get('policyRevisionId') != proposal['policyRevisionId'] or window.get('targetRefs') != ids or window.get('state') != 'inactive' or window.get('timezone') != 'Europe/Minsk':
        fail('Approved first window is missing or does not match the selected policy.')
    try:
        uuid.UUID(window['windowId'])
        start = datetime.datetime.fromisoformat(window['startsAt'].replace('Z', '+00:00'))
        end = datetime.datetime.fromisoformat(window['endsAt'].replace('Z', '+00:00'))
        if start.utcoffset() != datetime.timedelta(0) or end.utcoffset() != datetime.timedelta(0): fail('Window must use UTC instants.')
        local_start, local_end = start.astimezone(ZoneInfo('Europe/Minsk')), end.astimezone(ZoneInfo('Europe/Minsk'))
        if local_start.hour < 15 or local_start.date() != local_end.date() or end <= start or (end-start).total_seconds() > 9*3600 or window.get('localDate') != local_start.date().isoformat(): fail('Invalid Minsk window boundaries.')
        budget, remaining, grant, cooling = (window[k] for k in ('reliefBudgetSeconds','reliefRemainingSeconds','reliefMaxGrantSeconds','exitCoolingOffSeconds'))
        if any(type(x) is not int for x in (budget, remaining, grant, cooling)) or not (0 <= budget <= 3600 and remaining == budget and 1 <= grant <= 900 and 1 <= cooling <= 120): fail('Invalid initial budget or delay.')
        if any(window.get(k) is not None for k in ('reliefUntil','exitAvailableAt','exitUptimeDeadline','exitBootID','stateBeforeExitPending')): fail('Initial window already has a grant or exit.')
    except (KeyError, TypeError, ValueError): fail('Invalid approved first window.')
    ui = inspect_app(app_path)
    if ui['cdhash'] != proposal.get('uiCDHash') or not re.fullmatch(r'[0-9a-f]{40}', ui['cdhash'] or ''):
        fail('Current app signature no longer matches the proposal.')
    selected = []
    for identifier, app, expected in zip(ids, apps, requirements):
        actual = inspect_app(app.get('path', ''))
        if not re.fullmatch(r'[A-Z0-9]{10}', actual['teamID'] or '') or actual['signingID'] != identifier or actual['bundleID'] != identifier:
            fail('Target signature, Team ID, bundle ID or signing ID is invalid; ad-hoc is not accepted.')
        if actual['teamID'] != expected.get('teamID') or actual['requirement'] != expected.get('requirement') or not actual['requirement']:
            fail('Target signing identity changed since selection.')
        selected.append({'signingID': identifier, 'teamID': actual['teamID'], 'requirement': actual['requirement']})
    binary = read_regular(daemon_path)
    recovery = read_regular(recovery_path)
    if hashlib.sha256(binary).hexdigest() != daemon_sha or hashlib.sha256(recovery).hexdigest() != recovery_sha:
        fail('Candidate executable hash differs from the reviewed build.')
    trust = {'schemaVersion': 1, 'allowedUID': uid, 'uiCDHash': ui['cdhash'], 'blockedSigningIDs': ids,
             'blockedDomains': [], 'protectedDomains': PROTECTED_DOMAINS, 'signingRequirements': selected, 'approvedWindow': window}
    return trust, binary, recovery, proposal['policyRevisionId']


def check_root():
    info = os.lstat(ROOT)
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != ROOT_OWNER or info.st_mode & 0o077: fail('Unsafe candidate directory.')
    items = {x.name for x in ROOT.iterdir()}
    if items - FIXED_FILES: fail('Unexpected candidate directory contents.')
    for path in ROOT.iterdir(): read_regular(path, owner=ROOT_OWNER, private=True)
    return items


def verified_binary(name):
    manifest = json.loads(read_regular(ROOT/'manifest.json', owner=ROOT_OWNER, private=True, limit=32768))
    content = read_regular(ROOT/name, owner=ROOT_OWNER, private=True)
    if manifest.get(name) != hashlib.sha256(content).hexdigest(): fail('Installed candidate binary integrity check failed.')
    return ROOT/name


def rename_exclusive(source, destination):
    libc = ctypes.CDLL(None, use_errno=True)
    renamex = getattr(libc, 'renamex_np', None)
    if renamex is None: fail('Exclusive directory rename is unavailable; no installation attempted.')
    renamex.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
    renamex.restype = ctypes.c_int
    if renamex(os.fsencode(source), os.fsencode(destination), 0x00000004) != 0:
        err = ctypes.get_errno(); raise OSError(err, os.strerror(err))


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY | os.O_CLOEXEC)
    try: os.fsync(fd)
    finally: os.close(fd)


def write_private(directory, name, data, mode):
    fd = os.open(directory/name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, 'wb') as f: f.write(data); f.flush(); os.fsync(f.fileno())


def install(trust, daemon, recovery, revision):
    if os.path.lexists(ROOT) or os.path.lexists(SOCKET): fail('Candidate directory or socket already exists; nothing changed.')
    parent_info = os.lstat(ROOT.parent)
    if not stat.S_ISDIR(parent_info.st_mode) or parent_info.st_uid != ROOT_OWNER or parent_info.st_mode & 0o022: fail('Unsafe installation parent directory.')
    stage = pathlib.Path(tempfile.mkdtemp(prefix='.focusdeck-candidate-', dir=ROOT.parent)); os.chmod(stage, 0o700)
    config = json.dumps(trust, sort_keys=True, indent=2).encode(); trust_digest = hashlib.sha256(config).hexdigest()
    ledger = json.dumps({'schemaVersion': 2, 'policy': trust['approvedWindow'], 'bootID': 'prepared', 'uptimeDeadline': 0, 'trustDigest': trust_digest}, sort_keys=True).encode()
    manifest = json.dumps({DAEMON: hashlib.sha256(daemon).hexdigest(), RECOVERY: hashlib.sha256(recovery).hexdigest(),
                           'root-config.json': trust_digest, 'policyRevisionId': revision}, sort_keys=True, indent=2).encode()
    try:
        for name, data, mode in [(DAEMON, daemon, 0o700), (RECOVERY, recovery, 0o700), ('root-config.json', config, 0o600),
                                 (POLICY, ledger, 0o600), ('manifest.json', manifest, 0o600), ('daemon.lock', b'', 0o600)]:
            write_private(stage, name, data, mode)
        sync_directory(stage)
        # Atomic no-replace rename: a competing install cannot be overwritten.
        rename_exclusive(stage, ROOT)
        sync_directory(ROOT.parent)
        # Ready is the final operation. Missing ready always prevents launch.
        ready = json.dumps({'trustDigest': trust_digest, 'policyRevisionId': revision}, sort_keys=True).encode()
        write_private(ROOT, 'ready.json', ready, 0o600)
        sync_directory(ROOT)
    except Exception:
        print(f'Installation outcome may be incomplete; inspect {stage} and {ROOT}. Do not run or reinitialize.', file=sys.stderr)
        raise
    print('Candidate installed idle with approved first ledger. No daemon started, no apps signalled, no network changed.')


def check_config():
    manifest = json.loads(read_regular(ROOT/'manifest.json', owner=ROOT_OWNER, private=True, limit=32768))
    data = read_regular(ROOT/'root-config.json', owner=ROOT_OWNER, private=True, limit=128*1024)
    digest = hashlib.sha256(data).hexdigest()
    if manifest.get('root-config.json') != digest: fail('Root trust configuration changed.')
    ready = json.loads(read_regular(ROOT/'ready.json', owner=ROOT_OWNER, private=True, limit=32768))
    if ready.get('trustDigest') != digest or ready.get('policyRevisionId') != manifest.get('policyRevisionId'): fail('Installation is not durably marked ready.')
    trust = json.loads(data); ledger = json.loads(read_regular(ROOT/POLICY, owner=ROOT_OWNER, private=True, limit=128*1024))
    approved = trust.get('approvedWindow', {}); policy = ledger.get('policy', {})
    fixed = ('windowId','policyRevisionId','targetRefs','timezone','localDate','startsAt','endsAt','reliefBudgetSeconds','reliefMaxGrantSeconds','exitCoolingOffSeconds')
    if ledger.get('schemaVersion') != 2 or ledger.get('trustDigest') != digest or any(policy.get(k) != approved.get(k) for k in fixed): fail('Ledger does not match the approved policy.')
    if type(policy.get('reliefRemainingSeconds')) is not int or not 0 <= policy['reliefRemainingSeconds'] <= policy.get('reliefBudgetSeconds', -1): fail('Ledger budget is invalid.')
    # Re-establish durable directory entries before a later foreground run.
    # If either fsync is unsupported/fails, the status remains unknown.
    sync_directory(ROOT); sync_directory(ROOT.parent)
    return ledger


def recover():
    check_root(); binary = verified_binary(RECOVERY)
    if not os.path.lexists(SOCKET) and not (ROOT/POLICY).exists() and not (ROOT/'daemon.lock').exists():
        print('Candidate already idle; no policy or daemon found.'); return
    subprocess.run([str(binary), '--recover'], check=True)


def remove():
    if not os.path.lexists(ROOT):
        if os.path.lexists(SOCKET): fail('A socket exists without the candidate directory; inspect it.')
        print('Candidate already removed.'); return
    items = check_root()
    if os.path.lexists(SOCKET): fail('Socket exists; recover/inspect before removing anything.')
    preserved = {LOG, POLICY, 'root-config.json', 'manifest.json'}
    if items <= preserved:
        print('Candidate already removed; ledger and local event history are preserved.'); return
    verified_binary(DAEMON); verified_binary(RECOVERY); ledger = check_config()
    if ledger['policy'].get('state') not in ('exit', 'expired'): fail('Policy is not terminal; explicitly recover before removing anything.')
    lockfd = None
    try:
        if 'daemon.lock' in items:
            lockfd = os.open(ROOT/'daemon.lock', os.O_RDWR | os.O_NOFOLLOW | os.O_CLOEXEC)
            try: fcntl.flock(lockfd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError: fail('Candidate daemon is active; nothing removed.')
        for name in sorted(items - preserved): os.unlink(ROOT/name)
        print('Candidate removed; ledger, trust and local event history are preserved. Explicit root removal of those files is an administrator bypass.')
    finally:
        if lockfd is not None: os.close(lockfd)


def main():
    if len(sys.argv) < 2: fail('Choose plan, install-idle, run, recover or remove.')
    action, args = sys.argv[1], sys.argv[2:]
    if action in ('plan', 'install-idle'):
        if len(args) != 7: fail('Expected PROPOSAL APP DAEMON RECOVERY PROPOSAL_SHA256 DAEMON_SHA256 RECOVERY_SHA256.')
        if action == 'install-idle' and os.geteuid() != 0: fail('install-idle requires root.')
        uid = int(os.environ['SUDO_UID']) if os.geteuid() == 0 and os.environ.get('SUDO_UID', '').isdigit() else os.getuid()
        trust, daemon, recovery_binary, revision = draft(*args, uid)
        if action == 'plan':
            selected_paths = json.loads(read_regular(args[0], owner=uid, private=True, limit=128*1024))['targetApplications']
            print(json.dumps({'policyRevisionId': revision, 'uid': uid, 'uiCDHash': trust['uiCDHash'], 'selectedApplications': selected_paths, 'selectedSigningRequirements': trust['signingRequirements'], 'approvedWindow': trust['approvedWindow'], 'proposalSHA256': args[4], 'daemonSHA256': args[5], 'recoverySHA256': args[6], 'action': 'review_only_no_changes'}, indent=2))
        else: install(trust, daemon, recovery_binary, revision)
    else:
        if os.geteuid() != 0 or args: fail('This action requires root and no extra arguments.')
        if action == 'run':
            items = check_root(); executable = verified_binary(DAEMON); verified_binary(RECOVERY); ledger = check_config()
            if os.path.lexists(SOCKET): fail('A socket already exists; recover/inspect before running.')
            if ledger['policy'].get('state') in ('exit', 'expired'): fail('The approved window is terminal; it cannot be restarted.')
            os.execv(executable, [str(executable), '--candidate-daemon'])
        elif action == 'recover': recover()
        elif action == 'remove': remove()
        else: fail('Unknown action.')

if __name__ == '__main__':
    try: main()
    except (ValueError, OSError, KeyError, json.JSONDecodeError, subprocess.CalledProcessError) as error: raise SystemExit(str(error))
