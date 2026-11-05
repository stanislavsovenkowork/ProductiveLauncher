#!/usr/bin/env python3
"""Root-only, opt-in installation/removal of the bounded V2 synthetic fixture.

Never touches launchd, hosts, DNS or the legacy helper. Paths are fixed.
"""
import fcntl
import hashlib
import json
import os
import pathlib
import re
import stat
import subprocess
import sys

ROOT = pathlib.Path('/Library/Application Support/FocusDeckGuardV2Fixture')
SOCKET = pathlib.Path('/var/run/dev.stanislav.focusdeck.guard-v2-fixture.sock')
TARGET = 'dev.stanislav.focusdeck.guard-fixture'
ALLOWED = {'root-config.json', 'FocusDeckV2FixtureGuard', 'FocusDeckV2FixtureControl', 'daemon.lock', 'window-v2-fixture.json', 'fixture-events.jsonl', 'socket-inode.json'}


def fail(message):
    raise SystemExit(message)


def check_directory():
    info = os.lstat(ROOT)
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
        fail('Unsafe fixture directory; stop and inspect it.')


def install(args):
    if len(args) != 8:
        fail('Usage: install UID APP GUARD GUARD_SHA FIXTURE FIXTURE_SHA CONTROL CONTROL_SHA')
    uid_text, app, binary, expected, fixture_path, fixture_expected, control_path, control_expected = args
    if not uid_text.isdigit() or int(uid_text) < 500 or not re.fullmatch(r'[0-9a-f]{64}', expected) or not re.fullmatch(r'[0-9a-f]{64}', fixture_expected) or not re.fullmatch(r'[0-9a-f]{64}', control_expected):
        fail('Invalid UID or SHA256.')
    if os.path.lexists(ROOT) or os.path.lexists(SOCKET):
        fail('Fixture directory or socket already exists; nothing was changed.')
    app_path = pathlib.Path(app)
    subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(app_path)], check=True, stdout=subprocess.DEVNULL)
    result = subprocess.run(['/usr/bin/codesign', '-dv', '--verbose=4', str(app_path)], check=True, text=True, capture_output=True)
    match = re.search(r'^CDHash=([0-9a-f]{40})$', result.stderr, re.MULTILINE)
    if not match:
        fail('Could not verify the app CDHash.')
    fd = os.open(binary, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size > 20 * 1024 * 1024:
            fail('Invalid fixture daemon binary.')
        with os.fdopen(fd, 'rb', closefd=False) as f:
            content = f.read()
    finally:
        os.close(fd)
    actual = hashlib.sha256(content).hexdigest()
    if actual != expected:
        fail('Daemon hash differs from the reviewed build; nothing was changed.')
    fixture_fd = os.open(fixture_path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        fixture_info = os.fstat(fixture_fd)
        if not stat.S_ISREG(fixture_info.st_mode) or fixture_info.st_size > 20 * 1024 * 1024: fail('Invalid synthetic fixture binary.')
        with os.fdopen(fixture_fd, 'rb', closefd=False) as f: fixture_bytes = f.read()
    finally: os.close(fixture_fd)
    if hashlib.sha256(fixture_bytes).hexdigest() != fixture_expected: fail('Synthetic fixture hash changed; nothing was changed.')
    subprocess.run(['/usr/bin/codesign', '--verify', '--strict', fixture_path], check=True, stdout=subprocess.DEVNULL)
    detail = subprocess.run(['/usr/bin/codesign', '-dv', '--verbose=4', fixture_path], check=True, text=True, capture_output=True).stderr
    fixture_identifier = re.search(r'^Identifier=([^\r\n]+)$', detail, re.MULTILINE)
    fixture_cdhash = re.search(r'^CDHash=([0-9a-f]{40})$', detail, re.MULTILINE)
    if not fixture_identifier or fixture_identifier.group(1) != TARGET or not fixture_cdhash: fail('Synthetic fixture signing identity is invalid.')
    control_fd = os.open(control_path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        control_info = os.fstat(control_fd)
        if not stat.S_ISREG(control_info.st_mode) or control_info.st_size > 20 * 1024 * 1024: fail('Invalid fixture control binary.')
        with os.fdopen(control_fd, 'rb', closefd=False) as f: control_bytes = f.read()
    finally: os.close(control_fd)
    if hashlib.sha256(control_bytes).hexdigest() != control_expected: fail('Fixture control hash changed; nothing was changed.')
    os.mkdir(ROOT, 0o700)
    try:
        for name, data, mode in [
            ('FocusDeckV2FixtureGuard', content, 0o700),
            ('FocusDeckV2FixtureControl', control_bytes, 0o700),
            ('root-config.json', json.dumps({'schemaVersion': 1, 'allowedUID': int(uid_text), 'uiCDHash': match.group(1),
              'blockedSigningIDs': [TARGET], 'fixtureCDHash': fixture_cdhash.group(1), 'daemonSHA256': actual, 'controlSHA256': control_expected, 'blockedDomains': [],
              'protectedDomains': ['gitlab.eskhata.com', 'openai.com', 'chatgpt.com']}).encode(), 0o600),
        ]:
            out = os.open(ROOT / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
            with os.fdopen(out, 'wb') as f:
                f.write(data); f.flush(); os.fsync(f.fileno())
    except Exception:
        # Fresh private directory only: preserve partial setup for explicit inspection.
        print(f'Partial fixture setup retained at {ROOT}; inspect before removal.', file=sys.stderr)
        raise
    print(f'Fixture installed idle: UID={uid_text}, UI CDHash={match.group(1)}, daemon SHA256={actual}, fixture CDHash={fixture_cdhash.group(1)}. No daemon started.')


def verified_installed_binary(name, hash_field):
    check_directory()
    config_path = ROOT / 'root-config.json'
    config_info = os.lstat(config_path)
    if not stat.S_ISREG(config_info.st_mode) or config_info.st_uid != 0 or config_info.st_mode & 0o077 or config_info.st_size > 16384:
        fail('Unsafe fixture configuration.')
    config = json.loads(config_path.read_bytes())
    target = ROOT / name; info = os.lstat(target)
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077 or info.st_size > 20 * 1024 * 1024:
        fail('Unsafe fixture executable.')
    if hashlib.sha256(target.read_bytes()).hexdigest() != config.get(hash_field): fail('Fixture executable hash changed.')
    return target


def audit():
    check_directory()
    path = ROOT / 'fixture-events.jsonl'
    if not path.exists():
        print('Fixture event log is absent; no signal is confirmed.'); return
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077 or info.st_size > 256 * 1024:
            fail('Unsafe or oversized fixture event log.')
        with os.fdopen(fd, 'rb', closefd=False) as stream: rows = stream.read().splitlines()
    finally: os.close(fd)
    allowed = {'fixture_attempt', 'fixture_signal_sent', 'policy_updated', 'explicit_fixture_stop', 'window_expired', 'relief_ended'}
    counts = {name: 0 for name in allowed}
    for row in rows:
        event = json.loads(row).get('event')
        if event not in allowed: fail('Unexpected fixture event; no conclusion reported.')
        counts[event] += 1
    print(json.dumps({'scope': 'synthetic_fixture_only', 'counts': counts}, sort_keys=True))


def socket_identity_matches(path, marker, expected_owner):
    try:
        info = os.lstat(path)
        value = json.loads(marker)
        return stat.S_ISSOCK(info.st_mode) and info.st_uid == expected_owner and value.get('device') == info.st_dev and value.get('inode') == info.st_ino
    except (OSError, ValueError, TypeError, AttributeError):
        return False


def remove():
    if not os.path.lexists(ROOT):
        if os.path.lexists(SOCKET): fail('Directory absent but socket exists; inspect it before cleanup.')
        print('No V2 fixture directory exists.'); return
    check_directory()
    items = {p.name for p in ROOT.iterdir()}
    if items - ALLOWED:
        fail('Unexpected fixture directory contents; nothing removed.')
    for item in ROOT.iterdir():
        info = os.lstat(item)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0:
            fail('Unexpected ownership or file type; nothing removed.')
    lock_fd = None
    try:
        if (ROOT / 'daemon.lock').exists():
            lock_fd = os.open(ROOT / 'daemon.lock', os.O_RDWR | os.O_NOFOLLOW)
            try: fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError: fail('Fixture daemon is still running; stop it first.')
        if os.path.lexists(SOCKET):
            marker = ROOT / 'socket-inode.json'
            if not marker.exists() or not socket_identity_matches(SOCKET, marker.read_bytes(), 0): fail('Unverified socket; nothing removed.')
        if os.path.lexists(SOCKET): os.unlink(SOCKET)
        for item in ROOT.iterdir(): os.unlink(item)
        os.rmdir(ROOT)
        print('V2 fixture removed. No other services, files or network settings were changed.')
    finally:
        if lock_fd is not None: os.close(lock_fd)


def main():
    if os.geteuid() != 0: fail('This fixture administrator must run as root.')
    if len(sys.argv) < 2: fail('Choose install, run, stop, audit or remove.')
    if sys.argv[1] == 'install': install(sys.argv[2:])
    elif sys.argv[1] == 'run' and len(sys.argv) == 2:
        executable = verified_installed_binary('FocusDeckV2FixtureGuard', 'daemonSHA256')
        os.execv(executable, [str(executable), '--fixture-daemon'])
    elif sys.argv[1] == 'stop' and len(sys.argv) == 2:
        verified_installed_binary('FocusDeckV2FixtureGuard', 'daemonSHA256')
        executable = verified_installed_binary('FocusDeckV2FixtureControl', 'controlSHA256')
        os.execv(executable, [str(executable), '--stop'])
    elif sys.argv[1] == 'audit' and len(sys.argv) == 2: audit()
    elif sys.argv[1] == 'remove' and len(sys.argv) == 2: remove()
    else: fail('Invalid command.')

if __name__ == '__main__': main()
