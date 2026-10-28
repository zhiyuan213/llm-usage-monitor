"""MeterTrail desktop model tester. Python 3.10+, standard library only."""
import json
import queue
import threading
import time
import uuid
import urllib.request
from datetime import datetime, timezone
import tkinter as tk
from tkinter import ttk, messagebox


def post(url, key, body, expected):
    if not url.startswith('https://') and not url.startswith('http://localhost:'):
        raise ValueError('请使用 HTTPS 地址（本机测试可用 http://localhost:端口）')
    request = urllib.request.Request(url, json.dumps(body).encode(),
        {'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=90) as response:
        result = json.load(response)
        if response.status != expected:
            raise ValueError('响应状态不符合预期：' + str(response.status))
        return result


def events(response, provider, model, started, elapsed):
    trace = uuid.uuid4().hex
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
        event('observation', dict(common, name='模型回答', observationType='generation',
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
        root.title('MeterTrail · 真实模型测试工具')
        root.geometry('1000x800')
        root.minsize(700, 600)
        style = ttk.Style()
        style.theme_use('clam')
        style.configure('TButton', padding=8)
        frame = ttk.Frame(root, padding=20)
        frame.pack(fill='both', expand=True)
        self.values = {}
        fields = [('api', '模型完整 API 地址', 'https://api.deepseek.com/chat/completions'),
            ('key', '模型 API Key', ''), ('model', '模型名称', 'deepseek-chat'),
            ('provider', '供应商（用于价格匹配）', 'deepseek'),
            ('endpoint', 'MeterTrail 地址', 'https://metertrail.com'),
            ('meterkey', 'MeterTrail 项目 Key', ''), ('count', '累计多少次调用后上报（1–50）', '1')]
        for row, (name, label, default) in enumerate(fields):
            ttk.Label(frame, text=label).grid(row=row, column=0, sticky='w', pady=5)
            value = tk.StringVar(value=default)
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
        self.status = tk.StringVar(value='就绪 · 密钥仅保存在本次运行内存中')
        ttk.Label(frame, textvariable=self.status, wraplength=850).grid(row=10, columnspan=2, sticky='w')
        self.output = tk.Text(frame, wrap='word', state='disabled', font=('Microsoft YaHei UI', 11))
        self.output.grid(row=11, columnspan=2, sticky='nsew', pady=(10, 0))
        frame.rowconfigure(11, weight=1)
        root.protocol('WM_DELETE_WINDOW', self.close)
        root.after(100, self.tick)

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
                self.messages.put(('log', '操作失败：' + type(exc).__name__ + '。检查网络、地址、密钥；待上报数据仍保留，可重试。'))
            finally:
                self.messages.put(('done', ''))
        threading.Thread(target=worker, daemon=True).start()

    def ask(self):
        try:
            cfg = self.config()
            question = self.question.get('1.0', 'end').strip()
            if not question:
                raise ValueError('请输入问题')
        except ValueError as exc:
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
            items = events(response, cfg['provider'], cfg['model'], started, elapsed)
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
            self.outbox = {'endpoint': endpoint, 'key': key, 'batchId': None,
                'size': len(self.pending), 'body': {'schemaVersion': '1.0',
                    'agent': {'serviceName': 'desktop-model-tester', 'environment': 'test', 'instanceId': 'desktop-1'},
                    'events': [event for item in self.pending for event in item['events']]}}
        box = self.outbox
        if not box['batchId']:
            result = post(box['endpoint'].rstrip('/') + '/v1/events/batch', box['key'], box['body'], 202)
            box['batchId'] = result['batchId']
        self.messages.put(('log', '已接收 HTTP 202，批次：' + box['batchId'] + '，正在查询处理结果…'))
        for _ in range(30):
            request = urllib.request.Request(box['endpoint'].rstrip('/') + '/v1/batches/' + box['batchId'],
                headers={'Authorization': 'Bearer ' + box['key']})
            with urllib.request.urlopen(request, timeout=15) as response:
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
        self.status.set(('处理中…' if self.busy else '就绪') + f' · 待上报 {len(self.pending)} 次调用（每次 1 Trace + 1 Observation）')
        self.root.after(100, self.tick)

    def close(self):
        if (self.pending or self.busy) and not messagebox.askyesno('退出', '还有待上报数据或请求处理中，退出将丢失内存数据。确定退出？'):
            return
        self.root.destroy()


if __name__ == '__main__':
    App(tk.Tk()).root.mainloop()
