"""Opt-in frozen-executable smoke test; synthetic localhost data only."""
import json
import tempfile
import threading
import time
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def verify(app, output):
    state = {'modelCalls': 0, 'batches': [], 'queries': 0}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def respond(self, status, body):
            payload = json.dumps(body).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            if self.path == '/chat/completions':
                if self.headers.get('Authorization') != 'Bearer synthetic-model-key':
                    self.respond(401, {'error': {'message': 'invalid synthetic key'}})
                    return
                state['modelCalls'] += 1
                self.respond(200, {'model': 'test-model', 'choices': [{'message': {'content': '模拟回答'}}],
                    'usage': {'prompt_tokens': 14, 'completion_tokens': 79, 'prompt_cache_hit_tokens': 0}})
            elif self.path == '/v1/events/batch':
                assert self.headers.get('Authorization') == 'Bearer synthetic-meter-key'
                state['batches'].append(body)
                self.respond(202, {'batchId': 'synthetic-batch', 'status': 'pending'})
            else:
                self.respond(404, {})

        def do_GET(self):
            assert self.path == '/v1/batches/synthetic-batch'
            state['queries'] += 1
            count = len(state['batches'][-1]['events'])
            self.respond(200, {'status': 'completed', 'received': count, 'accepted': count,
                'duplicates': 0, 'rejected': 0, 'errors': []})

    server = ThreadingHTTPServer(('localhost', 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    result = {}
    root = None
    try:
        with tempfile.TemporaryDirectory() as directory:
            app.config_path = lambda: Path(directory) / 'config.json'
            root = app.tk.Tk()
            root.withdraw()
            window = app.App(root)
            base = f'http://localhost:{server.server_port}'
            values = {'api': base + '/chat/completions', 'key': 'synthetic-model-key',
                'model': 'test-model', 'provider': 'synthetic', 'endpoint': base,
                'meterkey': 'synthetic-meter-key', 'count': '2'}
            for key, value in values.items():
                window.values[key].set(value)
            window.question.insert('1.0', '测试问题')

            def ask():
                window.ask()
                deadline = time.monotonic() + 15
                while window.busy and time.monotonic() < deadline:
                    root.update()
                    time.sleep(.02)
                root.update()
                assert not window.busy, 'Request did not complete'

            ask()
            assert len(window.pending) == 1 and len(state['batches']) == 0
            ask()
            assert state['modelCalls'] == 2 and len(state['batches']) == 1
            assert len(window.pending) == 0 and state['queries'] == 1
            events = state['batches'][0]['events']
            assert len(events) == 3 and len({e['eventId'] for e in events}) == 3
            assert [e['type'] for e in events] == ['trace', 'observation', 'observation']
            assert len({e['traceId'] for e in events}) == 1
            assert len({e['objectId'] for e in events}) == 3
            assert events[1]['spanId'] != events[2]['spanId']
            assert events[1]['data']['usage'] == {'inputTokens': 14, 'outputTokens': 79, 'cachedInputTokens': 0}
            assert events[0]['traceId'] == events[1]['traceId']
            raw = app.config_path().read_text(encoding='utf-8')
            assert 'synthetic-model-key' not in raw and 'synthetic-meter-key' not in raw
            restored = app.load_settings()
            assert all(str(restored[k]) == value for k, value in values.items())
            root.destroy()
            root = app.tk.Tk()
            root.withdraw()
            reopened = app.App(root)
            assert all(reopened.values[k].get() == value for k, value in values.items())
            window = reopened
            window.question.insert('1.0', '测试无效密钥')
            window.values['key'].set('synthetic-invalid-key')
            ask()
            assert 'HTTP 401' in window.output.get('1.0', 'end')
            assert len(window.pending) == 0
            window.values['key'].set(values['key'])
            window.values['count'].set('20')
            ask()
            assert len(window.pending) == 1 and len(state['batches']) == 1
            before = list(window.pending)
            settings = {key: value.get() for key, value in window.values.items()}
            window.clear_text()
            assert window.output.get('1.0', 'end').strip() == ''
            assert window.pending == before
            assert {key: value.get() for key, value in window.values.items()} == settings
            assert window.question.get('1.0', 'end').strip()
            window.upload()
            deadline = time.monotonic() + 15
            while window.busy and time.monotonic() < deadline:
                root.update()
                time.sleep(.02)
            assert not window.busy and not window.pending
            assert len(state['batches']) == 2 and len(state['batches'][-1]['events']) == 2
            assert state['batches'][-1]['events'][0]['traceId'] != events[0]['traceId']
            result = {'status': 'passed', 'calls': state['modelCalls'], 'events': len(events),
                'batchThreshold': 2, 'batchQueries': state['queries'], 'encryptedConfig': True,
                'restartRestoresConfig': True, 'invalidKeyShows401': True, 'manualFlush': True,
                'clearTextPreservesPendingAndSettings': True}
    except Exception:
        import traceback
        result = {'status': 'failed', 'diagnostic': traceback.format_exc()}
        raise
    finally:
        if root is not None:
            try:
                root.destroy()
            except app.tk.TclError:
                pass
        server.shutdown()
        Path(output).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
