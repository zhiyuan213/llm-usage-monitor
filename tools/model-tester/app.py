"""MeterTrail desktop model tester. Python 3.10+, bundled public CA certificates."""
import json
import queue
import threading
import time
import uuid
import urllib.request
import urllib.error
import ssl
import sys
import os
import base64
import ctypes
import tempfile
from pathlib import Path
import certifi
from datetime import datetime, timezone
import tkinter as tk
from tkinter import ttk, messagebox


def config_path():
    return Path(os.environ.get('LOCALAPPDATA', str(Path.home()))) / 'MeterTrailTester' / 'config.json'


def protect(data, decrypt=False):
    """Windows DPAPI: encrypted values can only be read by this Windows account."""
    from ctypes import wintypes
    class Blob(ctypes.Structure):
        _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_byte))]
    buffer = ctypes.create_string_buffer(data)
    source = Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)))
    result = Blob()
    crypt = ctypes.WinDLL('crypt32', use_last_error=True)
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    method = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
    if not method(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(result)):
        raise OSError('无法使用当前 Windows 账户加密或解密配置')
    try:
        return ctypes.string_at(result.data, result.size)
    finally:
        kernel.LocalFree(ctypes.cast(result.data, ctypes.c_void_p))


def save_settings(values):
    path = config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    secrets = json.dumps({k: values[k] for k in ('key', 'meterkey')}).encode()
    document = {'version': 1, 'settings': {k: v for k, v in values.items() if k not in ('key', 'meterkey')},
        'encryptedKeys': base64.b64encode(protect(secrets)).decode('ascii')}
    handle, temporary = tempfile.mkstemp(dir=path.parent, suffix='.tmp')
    try:
        with os.fdopen(handle, 'w', encoding='utf-8') as stream:
            json.dump(document, stream, ensure_ascii=False, indent=2)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def load_settings():
    path = config_path()
    if not path.exists():
        return {}
    document = json.loads(path.read_text(encoding='utf-8'))
    if document.get('version') != 1:
        raise ValueError('配置文件版本不兼容')
    secrets = json.loads(protect(base64.b64decode(document['encryptedKeys'], validate=True), decrypt=True))
    values = {k: str(v) for k, v in document['settings'].items() if k not in ('key', 'meterkey')}
    values.update({k: secrets[k] for k in ('key', 'meterkey')})
    return values


def open_request(request, timeout):
    # Bundle a CA file with the EXE instead of depending on the build machine's
    # OpenSSL paths, environment variables, or missing Windows certificate stores.
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.load_verify_locations(cafile=certifi.where())
    return urllib.request.urlopen(request, timeout=timeout, context=context)


def error_message(exc):
    if isinstance(exc, urllib.error.HTTPError):
        return f'接口返回 HTTP {exc.code}（{exc.reason}）'
    if isinstance(exc, FileNotFoundError):
        return '缺少运行文件：' + str(exc.filename or '证书或运行库文件')
    if isinstance(exc, urllib.error.URLError):
        return '网络连接失败：' + type(exc.reason).__name__
    return type(exc).__name__


def post(url, key, body, expected):
    if not url.startswith('https://') and not url.startswith('http://localhost:'):
        raise ValueError('请使用 HTTPS 地址（本机测试可用 http://localhost:端口）')
    request = urllib.request.Request(url, json.dumps(body).encode(),
        {'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
    with open_request(request, timeout=90) as response:
        result = json.load(response)
        if response.status != expected:
            raise ValueError('响应状态不符合预期：' + str(response.status))
        return result


def events(response, provider, model, started, elapsed, trace_id=None):
    trace = trace_id or uuid.uuid4().hex
    usage = response.get('usage') or {}
    cached = usage.get('prompt_cache_hit_tokens',
        (usage.get('prompt_tokens_details') or {}).get('cached_tokens'))
    common = {'status': 'success', 'durationMs': elapsed}
    def event(kind, data):
        item = {'eventId': str(uuid.uuid4()), 'revision': 1, 'type': kind,
            'objectId': kind + '-' + uuid.uuid4().hex, 'traceId': trace,
            'startedAt': started, 'data': data}
        if kind == 'observation':
            item['spanId'] = uuid.uuid4().hex[:16]
        return item
    return [event('trace', dict(common, name='桌面真实模型测试', feature='desktop-model-test')),
        event('observation', dict(common, name=response.get('model') or model, observationType='generation',
            provider=provider, model=response.get('model') or model,
            usage={'inputTokens': usage.get('prompt_tokens'),
                'outputTokens': usage.get('completion_tokens'), 'cachedInputTokens': cached}))]


class App:
    def __init__(self, root):
        self.root = root
        self.pending = []
        self.outbox = None
        self.messages = queue.Queue()
        self.busy = False
        root.title('MeterTrail · 真实模型测试工具 v4.0（多次调用共用 Trace）')
        root.geometry('1000x800')
        root.minsize(700, 600)
        style = ttk.Style()
        style.theme_use('clam')
        style.configure('TButton', padding=8)
        frame = ttk.Frame(root, padding=20)
        frame.pack(fill='both', expand=True)
        self.values = {}
        settings_error = False
        try:
            saved = load_settings()
        except Exception:
            saved, settings_error = {}, True
        fields = [('api', '模型完整 API 地址', 'https://api.deepseek.com/chat/completions'),
            ('key', '模型 API Key', ''), ('model', '模型名称', 'deepseek-chat'),
            ('provider', '供应商（用于价格匹配）', 'deepseek'),
            ('endpoint', 'MeterTrail 地址', 'https://metertrail.com'),
            ('meterkey', 'MeterTrail 项目 Key', ''), ('count', '每组模型调用次数（1–50，共用 Trace）', '1')]
        for row, (name, label, default) in enumerate(fields):
            ttk.Label(frame, text=label).grid(row=row, column=0, sticky='w', pady=5)
            value = tk.StringVar(value=saved.get(name, default))
            self.values[name] = value
            ttk.Entry(frame, textvariable=value, show='•' if name in ('key', 'meterkey') else '').grid(
                row=row, column=1, sticky='ew', padx=(15, 0), pady=5)
        frame.columnconfigure(1, weight=1)
        ttk.Label(frame, text='问题（每次为独立问答；不把问题和回答正文上传到 MeterTrail）').grid(row=7, columnspan=2, sticky='w', pady=(15, 5))
        self.question = tk.Text(frame, height=4, wrap='word', font=('Microsoft YaHei UI', 11))
        self.question.grid(row=8, columnspan=2, sticky='nsew')
        bar = ttk.Frame(frame)
        bar.grid(row=9, columnspan=2, sticky='ew', pady=10)
        self.send = ttk.Button(bar, text='提问并记录', command=self.ask)
        self.send.pack(side='left')
        self.flush = ttk.Button(bar, text='立即上报 / 重试未完成批次', command=self.upload)
        self.flush.pack(side='left', padx=10)
        self.save = ttk.Button(bar, text='保存配置', command=self.save_config)
        self.save.pack(side='left')
        ttk.Button(bar, text='清空文字', command=self.clear_text).pack(side='left', padx=10)
        self.status = tk.StringVar(value='就绪 · Key 使用 Windows 账户加密保存在本地')
        ttk.Label(frame, textvariable=self.status, wraplength=850).grid(row=10, columnspan=2, sticky='w')
        self.output = tk.Text(frame, wrap='word', state='disabled', font=('Microsoft YaHei UI', 11))
        self.output.grid(row=11, columnspan=2, sticky='nsew', pady=(10, 0))
        frame.rowconfigure(11, weight=1)
        if settings_error:
            self.log('本地配置无法读取或不属于当前 Windows 账户，请重新填写并保存。')
        root.protocol('WM_DELETE_WINDOW', self.close)
        root.after(100, self.tick)

    def clear_text(self):
        self.output.configure(state='normal')
        self.output.delete('1.0', 'end')
        self.output.configure(state='disabled')

    def log(self, text):
        self.output.configure(state='normal')
        self.output.insert('end', text + '\n\n')
        self.output.see('end')
        self.output.configure(state='disabled')

    def config(self):
        cfg = {k: v.get().strip() for k, v in self.values.items()}
        if not all(cfg.values()):
            raise ValueError('请填写全部配置')
        cfg['count'] = int(cfg['count'])
        if not 1 <= cfg['count'] <= 50:
            raise ValueError('累计次数应为 1–50')
        return cfg

    def save_config(self):
        try:
            save_settings(self.config())
            self.log('配置已保存：' + str(config_path()) + '（两个 Key 已加密）')
        except Exception as exc:
            messagebox.showerror('保存配置失败', str(exc))

    def run(self, action):
        if self.busy:
            return
        self.busy = True
        self.send.configure(state='disabled')
        self.flush.configure(state='disabled')
        def worker():
            try:
                action()
            except Exception as exc:
                # Never print server bodies or keys to the interface.
                self.messages.put(('log', '操作失败：' + error_message(exc) + '。检查网络、地址、密钥；已记录的待上报数据仍保留，可重试。'))
            finally:
                self.messages.put(('done', ''))
        threading.Thread(target=worker, daemon=True).start()

    def ask(self):
        try:
            cfg = self.config()
            question = self.question.get('1.0', 'end').strip()
            if not question:
                raise ValueError('请输入问题')
            save_settings(cfg)
        except (ValueError, OSError) as exc:
            messagebox.showerror('配置提示', str(exc))
            return
        def action():
            if self.outbox or (self.pending and self.pending[0]['destination'] != (cfg['endpoint'], cfg['meterkey'])):
                self.messages.put(('log', '请先上报之前的待处理数据，再继续调用或切换项目。'))
                return
            started = datetime.now(timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')
            begin = time.monotonic()
            response = post(cfg['api'], cfg['key'], {'model': cfg['model'],
                'messages': [{'role': 'user', 'content': question}], 'stream': False}, 200)
            elapsed = round((time.monotonic() - begin) * 1000)
            answer = response['choices'][0]['message'].get('content', '')
            trace_id = self.pending[0]['events'][0]['traceId'] if self.pending else None
            items = events(response, cfg['provider'], cfg['model'], started, elapsed, trace_id)
            self.pending.append({'destination': (cfg['endpoint'], cfg['meterkey']), 'events': items})
            self.messages.put(('log', '问题：' + question + '\n回答：' + str(answer) + '\n耗时：' + str(elapsed) + ' ms\n用量：' + json.dumps(items[1]['data']['usage'], ensure_ascii=False)))
            if len(self.pending) >= cfg['count']:
                self.do_upload()
        self.run(action)

    def upload(self):
        self.run(self.do_upload)

    def do_upload(self):
        if not self.outbox:
            if not self.pending:
                self.messages.put(('log', '没有待上报数据。'))
                return
            endpoint, key = self.pending[0]['destination']
            trace = self.pending[0]['events'][0].copy()
            observations = [item['events'][1] for item in self.pending]
            first = datetime.fromisoformat(trace['startedAt'].replace('Z', '+00:00'))
            finish = max(datetime.fromisoformat(item['startedAt'].replace('Z', '+00:00')).timestamp()
                + item['data']['durationMs'] / 1000 for item in observations)
            trace['data'] = dict(trace['data'], durationMs=max(0, round((finish - first.timestamp()) * 1000)))
            self.outbox = {'endpoint': endpoint, 'key': key, 'batchId': None,
                'size': len(self.pending), 'body': {'schemaVersion': '1.0',
                    'agent': {'serviceName': 'desktop-model-tester', 'environment': 'test', 'instanceId': 'desktop-1'},
                    'events': [trace, *observations]}}
        box = self.outbox
        if not box['batchId']:
            result = post(box['endpoint'].rstrip('/') + '/v1/events/batch', box['key'], box['body'], 202)
            box['batchId'] = result['batchId']
        self.messages.put(('log', '已接收 HTTP 202，批次：' + box['batchId'] + '，正在查询处理结果…'))
        for _ in range(30):
            request = urllib.request.Request(box['endpoint'].rstrip('/') + '/v1/batches/' + box['batchId'],
                headers={'Authorization': 'Bearer ' + box['key']})
            with open_request(request, timeout=15) as response:
                result = json.load(response)
            if result.get('status') == 'completed':
                self.messages.put(('log', '批次结果：' + json.dumps(result, ensure_ascii=False)))
                if result.get('rejected', 0):
                    self.messages.put(('log', '存在业务拒绝，请先检查批次错误；数据保留，避免误报成功。'))
                    return
                del self.pending[:box['size']]
                self.outbox = None
                return
            if result.get('status') not in ('pending', 'processing'):
                self.messages.put(('log', '批次未成功：' + json.dumps(result, ensure_ascii=False)))
                return
            time.sleep(1)
        self.messages.put(('log', '处理结果尚未确定，稍后点击重试继续查询同一批次。'))

    def tick(self):
        while not self.messages.empty():
            kind, value = self.messages.get()
            if kind == 'log':
                self.log(value)
            else:
                self.busy = False
                self.send.configure(state='normal')
                self.flush.configure(state='normal')
        self.status.set(('处理中…' if self.busy else '就绪') + f' · 当前组 {len(self.pending)} 次调用（整组 1 Trace，每次 1 Observation）')
        self.root.after(100, self.tick)

    def close(self):
        if (self.pending or self.busy) and not messagebox.askyesno('退出', '还有待上报数据或请求处理中，退出将丢失内存数据。确定退出？'):
            return
        self.root.destroy()


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--integration-test':
        from verification import verify
        verify(sys.modules[__name__], sys.argv[2])
    elif len(sys.argv) == 3 and sys.argv[1] == '--self-test':
        results = []
        for url in ('https://metertrail.com/api/me', 'https://api.deepseek.com/models'):
            try:
                with open_request(urllib.request.Request(url), timeout=20) as response:
                    results.append({'url': url, 'status': response.status})
            except urllib.error.HTTPError as exc:
                results.append({'url': url, 'status': exc.code})
            except Exception as exc:
                import traceback
                results.append({'url': url, 'error': error_message(exc), 'diagnostic': traceback.format_exc()})
        Path(sys.argv[2]).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    else:
        App(tk.Tk()).root.mainloop()
