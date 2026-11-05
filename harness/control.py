#!/usr/bin/env python3
"""Visible controls for the three independent LOCAL collectors; preserves all history."""
import argparse
import datetime as dt
import fcntl
import uuid
import json
import os
from pathlib import Path
import plistlib
import shlex
import shutil
import subprocess
import sys
import tempfile

SERVICES = {
    "review": "dev.focusdeck.local-review",
    "receiver": "dev.focusdeck.local-ingestion",
    "context": "dev.focusdeck.passive-context",
}


def root_path(value):
    root = Path(value)
    if not root.is_absolute():
        raise ValueError("absolute private root required")
    return root.resolve()


def status(root):
    root = root_path(root)
    result = {}
    for name, label in SERVICES.items():
        job = subprocess.run(["/bin/launchctl", "print", f"gui/{os.getuid()}/{label}"], capture_output=True, text=True)
        result[name] = {"loaded": job.returncode == 0,
                        "installed": (Path.home()/"Library/LaunchAgents"/(label+".plist")).exists()}
    return {"services": result, "externalMessages": False, "dataRoot": str(root),
            "survivesAppClosure": True, "automaticReinstallAfterStop": False}


def invoke(script, command, directory):
    if not script.is_file():
        raise ValueError("installed control helper missing")
    result = subprocess.run([sys.executable, str(script), command, "--directory", str(directory)],
                            capture_output=True, text=True)
    if result.returncode != 0:
        raise ValueError("local service action failed; history preserved")
    return json.loads(result.stdout)


def controls(root):
    here = Path(__file__).resolve().parent
    native = here/"native-control.py"
    if not native.is_file():
        native = here/"native/control.py"
    return {
        "review": (root/"runtime/scheduler.py", root),
        "receiver": (root/"receiver/runtime/receiver.py", root/"receiver"),
        "context": (native, root/"context"),
    }


def change_locked(root, enable):
    root = root_path(root)
    helpers = controls(root)
    failures = []
    before = status(root)
    order = ("receiver", "context", "review") if enable else ("review", "context", "receiver")
    for name in order:
        state = before["services"][name]
        if enable and state["loaded"]:
            continue
        # Existing owned helpers are retained outside the removable .app.
        script, directory = helpers[name]
        try:
            if not enable and not state["loaded"] and not state["installed"]:
                continue
            invoke(script, "install" if enable else "uninstall", directory)
        except (OSError, ValueError, KeyError):
            failures.append(name)
    after = status(root)
    remaining = [n for n,s in after["services"].items() if s["loaded"] != enable]
    failures = sorted(set(failures+remaining))
    result = {"requested": "start" if enable else "stop", "complete": not failures,
              "failures": failures, "services": after["services"], "historyPreserved": True,
              "protectionChanged": False, "externalMessagesSent": 0,
              "automaticReinstallAfterStop": False}
    record_control(root, result)
    return result


def record_control(root, result):
    # Explicit consent/control audit only; no background collection after stop.
    event = {"schemaVersion":1,"eventId":str(uuid.uuid4()),"sessionId":None,
             "occurredAt":dt.datetime.now(dt.timezone.utc).isoformat(),"source":"mac",
             "category":None,"type":"disabled" if result["requested"]=="stop" and result["complete"] else "health",
             "evidence":"unknown","coverage":"partial",
             "metrics":{"focusSeconds":None,"distractionSeconds":None,"maintenanceSeconds":None},
             "reason":"local_collection_"+result["requested"],
             "control":{"complete":result["complete"],"failures":result["failures"],"historyPreserved":True}}
    path=root/"control-events.jsonl"
    fd=os.open(path,os.O_APPEND|os.O_WRONLY|os.O_CREAT,0o600)
    with os.fdopen(fd,"a") as stream:
        stream.write(json.dumps(event,separators=(",",":"))+"\n");stream.flush();os.fsync(stream.fileno())


def change(root, enable):
    root=root_path(root)
    if not root.is_dir():raise ValueError("private installation missing")
    with (root/".control-operation.lock").open("a+") as lock:
        os.chmod(root/".control-operation.lock",0o600)
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise ValueError("another local control action is running")
        return change_locked(root,enable)


def private_write(path, content, executable=False):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(dir=path.parent, prefix=".control-")
    try:
        with os.fdopen(fd,"wb") as stream:
            stream.write(content)
        os.chmod(temporary,0o700 if executable else 0o600)
        os.replace(temporary,path)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


def deploy(root):
    root = root_path(root)
    if not root.is_dir():
        raise ValueError("prepare the local review before deploying controls")
    target = root/"control-runtime"
    target.mkdir(mode=0o700, exist_ok=True)
    source = Path(__file__).resolve()
    native = source.parent/"native/control.py"
    if not native.is_file():
        raise ValueError("source native control missing")
    private_write(target/"control.py",source.read_bytes())
    private_write(target/"native-control.py",native.read_bytes())
    for name, action in (("all-stop.command","stop"),("start-local.command","start")):
        command = " ".join(shlex.quote(p) for p in [sys.executable,str(target/"control.py"),action,"--directory",str(root)])
        private_write(target/name,("#!/bin/sh\nexec "+command+"\n").encode(),executable=True)
    return {"deployed": True,"directory":str(target), "stopCommand":str(target/"all-stop.command"),
            "startCommand":str(target/"start-local.command"),"servicesChanged":False,
            "scope":"Local review, local receiver and passive context only; no history deletion or network/app restrictions."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command",choices=["status","stop","start","deploy"])
    parser.add_argument("--directory",required=True)
    args = parser.parse_args()
    try:
        if args.command=="status": result=status(args.directory)
        elif args.command=="deploy": result=deploy(args.directory)
        else: result=change(args.directory,args.command=="start")
        print(json.dumps(result,indent=2))
        if result.get("complete") is False: raise SystemExit(1)
    except (OSError,ValueError,KeyError):
        parser.exit(1,"Local control failed; inspect service status. History was not deleted.\n")


if __name__=="__main__":main()
