#!/usr/bin/python3
"""Only the one-shot signed fixture, executed as uid 501 by the root QA wrapper."""
import json
import ctypes
import errno
import os
import socket
import subprocess
import sys
import time

if os.getuid() != 501 or len(sys.argv) != 5:
    raise SystemExit(2)
client, daemon_hash, fixture, daemon_pid = sys.argv[1:]
daemon_pid = int(daemon_pid)
sock = "/tmp/focusdeck-strict-root-qa-501.sock"
if fixture != "/Library/PrivilegedHelperTools/FocusDeckStrictQA/GuardFixture":
    raise SystemExit(2)
children = []

def command(action, *args):
    result = subprocess.run([client, sock, daemon_hash, action, *args],
                            capture_output=True, text=True, timeout=8, check=False)
    if result.returncode != 0:
        raise RuntimeError("client rejected " + action)
    value = json.loads(result.stdout)
    if not value.get("ok"):
        raise RuntimeError("client did not confirm " + action)
    return value

def start():
    child = subprocess.Popen([fixture], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    children.append(child)
    return child

def terminated(child):
    try:
        child.wait(timeout=8)
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError("synthetic fixture was not stopped") from exc
    if child.returncode >= 0:
        raise RuntimeError("synthetic fixture ended without a signal")

try:
    first = start()
    command("bind", str(first.pid))
    enrolled_at = time.monotonic()
    command("enroll")
    terminated(first)
    requested = command("request-exit")
    command("raw-confirm")
    refreshed = command("status")
    if (requested["clock"] != "verified" or refreshed["clock"] != "verified"
        or refreshed["state"] != "exitPending" or not refreshed["requested"]
        or not isinstance(requested["remaining"], int) or not isinstance(refreshed["remaining"], int)
        or not 0 < refreshed["remaining"] <= requested["remaining"] <= 3600):
        raise RuntimeError("fresh client did not preserve the exit countdown")
    # Signal 0 asks the kernel about permission without sending a signal. The
    # PID is confirmed against both the root socket peer and our root parent.
    peer = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    peer.settimeout(2)
    peer.connect(sock)
    libc = ctypes.CDLL(None, use_errno=True)
    uid, gid, pid, size = ctypes.c_uint(), ctypes.c_uint(), ctypes.c_int(), ctypes.c_uint(ctypes.sizeof(ctypes.c_int))
    if libc.getpeereid(peer.fileno(), ctypes.byref(uid), ctypes.byref(gid)) != 0 or uid.value != 0:
        raise RuntimeError("root peer was not confirmed")
    if libc.getsockopt(peer.fileno(), 0, 2, ctypes.byref(pid), ctypes.byref(size)) != 0 or pid.value != daemon_pid:
        raise RuntimeError("fixture daemon PID did not match the root peer")
    try:
        os.kill(daemon_pid, 0)
        raise RuntimeError("user unexpectedly has permission to signal fixture daemon")
    except OSError as exc:
        if exc.errno != errno.EPERM:
            raise
    finally:
        peer.close()
    # The first IPC clients have exited. No second binding is sent.
    second = start()
    terminated(second)
    # There is no client connected while the authority reaches natural expiry.
    time.sleep(max(0, enrolled_at + 93 - time.monotonic()))
    status = command("status")
    if status["state"] != "expired" or status["requested"]:
        raise RuntimeError("natural expiry was not observed")
    released = start()
    time.sleep(2)
    if released.poll() is not None:
        raise RuntimeError("fixture did not remain running after expiry")
    print(json.dumps({"result":"PASS", "scope":"signed synthetic fixture only",
                      "checks":["initial signal", "early exit refused", "new client retains deadline", "user signal permission denied", "reopen without rebind", "natural expiry releases"]}, sort_keys=True))
except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as exc:
    print(json.dumps({"result":"FAIL", "reason":str(exc)}, sort_keys=True))
    raise SystemExit(1)
finally:
    for child in children:
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=2)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
