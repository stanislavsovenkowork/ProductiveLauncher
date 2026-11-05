"""Own ephemeral loopback server only; never contacts installed personal receiver."""
import http.server
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import uuid

requests = []
mode = 'accepted'
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        requests.append(('GET', self.path, self.headers.get('Authorization')))
        self.send_response(200); self.end_headers(); self.wfile.write(b'{}')
    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        requests.append(('POST', self.path, self.headers.get('Authorization'), data))
        self.send_response({'accepted': 202, 'conflict': 409, 'redirect': 307}[mode])
        if mode == 'redirect': self.send_header('Location', f'http://127.0.0.1:{self.server.server_port}/must-not-follow')
        self.end_headers(); self.wfile.write(b'{}')

with tempfile.TemporaryDirectory(prefix='focusdeck-receiver-tests-') as tmp:
    root = Path(tmp)
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    try:
        token = uuid.uuid4().hex
        token_file = root/'fixture.token'; token_file.write_text(token); token_file.chmod(0o600)
        config = root/'config.json'
        config.write_text(json.dumps(dict(schemaVersion=1, host='127.0.0.1', port=server.server_port, tokenFile=str(token_file))))
        journal = root/'journal'; journal.mkdir()
        event = dict(schemaVersion=1, eventId=str(uuid.uuid4()), sessionId=str(uuid.uuid4()), occurredAt='2026-10-28T09:00:00Z', source='mac', category='study', type='started', evidence='timer', metrics={'focusSeconds':None})
        (journal/'events.jsonl').write_text(json.dumps(event)+'\n')
        env = dict(os.environ, FOCUSDECK_DATA_DIR=str(journal), FOCUSDECK_RECEIVER_CONFIG=str(config))
        def run():
            return subprocess.check_output([str(Path(__file__).resolve().parents[1]/'build/receiver-client-tests'), str(journal)], env=env, text=True)
        assert 'журнал передан' in run()
        assert requests[-1][0:3] == ('POST', '/v1/events', 'Bearer '+token)
        assert requests[-1][3]['events'] == [event]
        assert json.loads((journal/'receiver-cursor.json').read_text()) == 1
        assert 'журнал передан' in run() and requests[-1][0] == 'GET'
        for mode in ['conflict', 'redirect']:
            (journal/'receiver-cursor.json').unlink(missing_ok=True)
            before = len(requests)
            status = run()
            assert not (journal/'receiver-cursor.json').exists()
            assert len(requests) == before+1 and requests[-1][0] == 'POST'
            assert ('Конфликт' in status) if mode == 'conflict' else ('недоступна' in status)
        print('Loopback receiver accepted/cursor/retry/409/redirect-refusal fixture tests passed; no installed receiver contacted.')
    finally:
        server.shutdown(); server.server_close()
