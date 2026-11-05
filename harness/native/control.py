#!/usr/bin/env python3
"""Prepare/install only a private, explicit-path passive foreground observer."""
import argparse
import hashlib
import json
import os
import pathlib
import plistlib
import shutil
import subprocess
import tempfile

LABEL = 'dev.focusdeck.passive-context'


def absolute(value):
    path = pathlib.Path(value)
    if not path.is_absolute():raise ValueError('absolute path required')
    return path.resolve()


def write(path, content, mode=0o600):
    fd, temporary = tempfile.mkstemp(dir=path.parent,prefix='.observer-')
    try:
        with os.fdopen(fd,'wb') as stream:stream.write(content)
        os.chmod(temporary,mode)
        os.replace(temporary,path)
    finally:
        if os.path.exists(temporary):os.unlink(temporary)


def prepare(directory, binary, snapshot=None, retention=7):
    directory, binary = absolute(directory), absolute(binary)
    if not binary.is_file() or not os.access(binary,os.X_OK):raise ValueError('built observer executable required')
    if any(p in ('LaunchAgents','LaunchDaemons') for p in directory.parts):raise ValueError('prepare never installs')
    repository = pathlib.Path(__file__).resolve().parents[2]
    if directory == repository or repository in directory.parents:raise ValueError('private data must be outside repository')
    if not 1 <= retention <= 365:raise ValueError('retention must be 1..365 days')
    if (directory/'observer-config.json').exists():raise FileExistsError('existing observer config preserved')
    directory.mkdir(mode=0o700,parents=True,exist_ok=True)
    os.chmod(directory,0o700)
    executable = directory/'FocusDeckContextObserver'
    data = binary.read_bytes()
    write(executable,data,0o700)
    config = {'schemaVersion':1,'binary':str(executable),'sha256':hashlib.sha256(data).hexdigest(),
              'output':str(directory/'observer-events.jsonl'),'retentionDays':retention,
              'sessionSnapshot':str(absolute(snapshot)) if snapshot else None,
              'scope':'Foreground bundle identifiers and elapsed context only; attention/productivity unknown.',
              'network':False,'windowTitles':False,'urls':False,'keystrokes':False}
    write(directory/'observer-config.json',(json.dumps(config,indent=2)+'\n').encode())
    arguments = [str(executable),'--output',config['output'],'--retention-days',str(retention)]
    if snapshot:arguments.extend(['--session-snapshot',config['sessionSnapshot']])
    plist = {'Label':LABEL,'ProgramArguments':arguments,'RunAtLoad':True,
             'KeepAlive':{'SuccessfulExit':False},'ThrottleInterval':30,'Umask':63,
             'StandardOutPath':'/dev/null','StandardErrorPath':'/dev/null'}
    write(directory/'observer.plist',plistlib.dumps(plist))
    return {'config':str(directory/'observer-config.json'),'plist':str(directory/'observer.plist'),
            'output':config['output'],'installed':False,'active':False}


def status(directory):
    directory = absolute(directory)
    target = pathlib.Path.home()/'Library/LaunchAgents'/f'{LABEL}.plist'
    result = subprocess.run(['/bin/launchctl','print',f'gui/{os.getuid()}/{LABEL}'],capture_output=True,text=True)
    return {'installed':target.exists(),'loaded':result.returncode == 0,
            'journalExists':(directory/'observer-events.jsonl').exists(),
            'launchctl':result.stdout if result.returncode == 0 else result.stderr}


def install(directory):
    directory = absolute(directory)
    config = json.loads((directory/'observer-config.json').read_text())
    binary = absolute(config['binary'])
    if binary != directory/'FocusDeckContextObserver' or hashlib.sha256(binary.read_bytes()).hexdigest() != config['sha256']:
        raise ValueError('observer integrity check failed')
    target = pathlib.Path.home()/'Library/LaunchAgents'/f'{LABEL}.plist'
    if target.exists():raise FileExistsError('existing observer job preserved')
    with (directory/'observer.plist').open('rb') as stream:job = plistlib.load(stream)
    if job.get('Label') != LABEL or job.get('ProgramArguments',[None])[0] != str(binary):
        raise ValueError('observer plist ownership mismatch')
    target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(directory/'observer.plist',target)
    os.chmod(target,0o600)
    subprocess.run(['/bin/launchctl','bootstrap',f'gui/{os.getuid()}',str(target)],check=True,capture_output=True,text=True)
    return status(directory)


def uninstall(directory):
    directory = absolute(directory)
    target = pathlib.Path.home()/'Library/LaunchAgents'/f'{LABEL}.plist'
    if target.exists():
        with target.open('rb') as stream:job = plistlib.load(stream)
        if job.get('Label') != LABEL or job.get('ProgramArguments',[None])[0] != str(directory/'FocusDeckContextObserver'):
            raise ValueError('refusing unrelated observer job')
        subprocess.run(['/bin/launchctl','bootout',f'gui/{os.getuid()}/{LABEL}'],capture_output=True,text=True)
        target.unlink()
    return {'installed':False,'dataRetained':str(directory),'automaticReinstall':False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command',required=True)
    generate = sub.add_parser('prepare')
    generate.add_argument('--directory',required=True)
    generate.add_argument('--binary',required=True)
    generate.add_argument('--session-snapshot')
    generate.add_argument('--retention-days',type=int,default=7)
    for name in ('install','uninstall','status'):
        action = sub.add_parser(name);action.add_argument('--directory',required=True)
    args = parser.parse_args()
    try:
        result = prepare(args.directory,args.binary,args.session_snapshot,args.retention_days) if args.command == 'prepare' else {'install':install,'uninstall':uninstall,'status':status}[args.command](args.directory)
        print(json.dumps(result,indent=2))
    except (ValueError,OSError,KeyError,subprocess.SubprocessError):
        parser.exit(1,'observer configuration/action failed; existing data preserved\n')


if __name__ == '__main__':main()
