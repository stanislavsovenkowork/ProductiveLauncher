#!/usr/bin/env python3
"""Own a QA scope until the entire test and its cleanup have returned."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import uuid

root = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('qa_scope', root / 'harness/qa_scope.py')
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)
log = Path(os.environ.get('FOCUSDECK_QA_SCOPE_LOG', str(Path.home() / 'Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl')))
ident = str(uuid.uuid4())
scope.append(log, 'start', ident)
env = dict(os.environ, FOCUSDECK_SITE_QA_SCOPE_ID=ident, FOCUSDECK_SITE_QA_SCOPE_LOG=str(log))
try:
    result = subprocess.run([str(root / 'macos-study/site-protection/tests/run.sh'), '--inside-scope'], env=env)
except BaseException:
    # Child state is unknown; do not claim that its observation interval ended.
    raise
if result.returncode == 75 or result.returncode < 0 or result.returncode >= 128:
    print('QA scope remains open: confirm all QA processes stopped before closing it.', file=sys.stderr)
    sys.exit(75)
scope.append(log, 'end', ident)
sys.exit(result.returncode)
