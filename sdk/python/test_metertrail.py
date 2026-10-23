import unittest
from metertrail import MeterTrail

class ClientTests(unittest.TestCase):
    def client(self, **kwargs):
        c=MeterTrail('test',api_key='lm_live_test',endpoint='http://localhost',**kwargs)
        c.paused='test'
        return c

    def test_copy_and_overflow(self):
        errors=[]
        c=self.client(on_error=errors.append)
        original={'type':'trace','objectId':'one','data':{'name':'first'}}
        self.assertTrue(c.record(original))
        original['data']['name']='mutated'
        self.assertEqual(c.queue[0][0]['data']['name'],'first')
        self.assertFalse(c.record({'data':{'name':'x'*9000}}))
        self.assertEqual(errors[-1]['code'],'EVENT_TOO_LARGE')
        c.size=16*1024*1024
        self.assertFalse(c.record(original))
        self.assertEqual(errors[-1]['code'],'BUFFER_OVERFLOW')
        c.close()

    def test_http_200_receipt_ends_without_polling_and_query_is_explicit(self):
        results=[]
        c=self.client(on_result=results.append)
        c.record({'type':'trace','objectId':'one','data':{}})
        seen=[]
        def request(path,body=None,deadline=None):
            seen.append(path)
            return (200,{'batchId':'one','status':'pending'}) if body else (200,{'status':'completed','accepted':1,'errors':[]})
        c._request=request
        c.paused=None
        self.assertTrue(c.flush())
        self.assertEqual(seen,['/v1/events/batch'])
        self.assertEqual(results[0]['status'],'pending')
        status,result=c.get_batch('one')
        self.assertEqual(status,200)
        self.assertEqual(result['status'],'completed')
        self.assertEqual(seen,['/v1/events/batch','/v1/batches/one'])
        c.close()

    def test_split_receipts_do_not_query_and_accept_legacy_202(self):
        results=[]
        c=self.client(on_result=results.append)
        c.record({'type':'trace','objectId':'one','data':{}})
        c.record({'type':'trace','objectId':'two','data':{}})
        seen=[]
        def request(path,body=None,deadline=None):
            seen.append(path)
            self.assertIsNotNone(body)
            return (413,{'code':'PAYLOAD_TOO_LARGE'}) if len(body['events'])>1 else (202,{'batchId':'one','status':'pending'})
        c._request=request
        c.paused=None
        self.assertTrue(c.flush())
        self.assertEqual(seen,['/v1/events/batch']*3)
        self.assertEqual(len(results),2)
        c.close()

    def test_quota_preserves_queue(self):
        c=self.client()
        c.record({'type':'trace','objectId':'one','data':{}})
        c._request=lambda *args,**kwargs:(429,{'code':'QUOTA_EXCEEDED'})
        c.paused=None
        self.assertFalse(c.flush())
        self.assertEqual(c.paused,'QUOTA_EXCEEDED')
        self.assertEqual(len(c.queue),1)
        c.close()

    def test_real_http_retry_close_and_auth_resume(self):
        import json
        import threading
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        for initial in (503, 401):
            with self.subTest(initial=initial):
                bodies, auth, results, errors = [], [], [], []
                statuses = [initial, 200]
                class Handler(BaseHTTPRequestHandler):
                    def log_message(self, *_): pass
                    def do_POST(self):
                        bodies.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
                        auth.append(self.headers['Authorization'])
                        status = statuses.pop(0)
                        data = {'batchId':'receipt','status':'pending'} if status == 200 else {'code':'INVALID_API_KEY' if status == 401 else 'TEMPORARY_FAILURE'}
                        raw = json.dumps(data).encode()
                        self.send_response(status)
                        self.send_header('Content-Length', str(len(raw)))
                        self.end_headers()
                        self.wfile.write(raw)
                server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                c = MeterTrail('http-test',api_key='synthetic',endpoint='http://127.0.0.1:'+str(server.server_port),on_result=results.append,on_error=errors.append)
                try:
                    c.record({'type':'trace','objectId':'one','data':{}})
                    if initial == 401:
                        self.assertFalse(c.flush())
                        self.assertEqual(c.paused,'INVALID_API_KEY')
                        self.assertEqual(len(c.queue),1)
                        self.assertFalse(c.flush())
                        self.assertEqual(len(bodies),1)
                        c.resume()
                    self.assertTrue(c.close())
                    self.assertEqual(c.queue,[])
                    self.assertEqual(len(bodies),2)
                    self.assertEqual(bodies[0],bodies[1])
                    self.assertEqual(auth,['Bearer synthetic']*2)
                    self.assertEqual(len(results),1)
                    self.assertFalse(c.record({'type':'trace','objectId':'late','data':{}}))
                    self.assertEqual(errors[-1]['code'],'CLOSED')
                finally:
                    c.stop.set(); c.wake.set(); c.thread.join(2)
                    server.shutdown(); server.server_close(); thread.join(2)

    def test_retry_exhaustion_and_deadline_preserve_queue_until_recovery(self):
        import json
        from unittest.mock import patch
        class Response:
            status=503
            headers={}
            def __enter__(self): return self
            def __exit__(self,*_): pass
            def read(self): return b'{"code":"SERVICE_UNAVAILABLE"}'
        errors=[]
        c=self.client(on_error=errors.append)
        c.record({'type':'trace','objectId':'exhaustion','data':{}})
        original=c.queue[0][0].copy()
        c.paused=None
        with patch('metertrail.urlopen',return_value=Response()) as transport, patch('metertrail.time.sleep'):
            self.assertFalse(c.flush())
            self.assertEqual(transport.call_count,6)
        self.assertEqual(c.queue[0][0],original)
        self.assertEqual(errors[-1]['code'],'SERVICE_UNAVAILABLE')
        with patch('metertrail.urlopen',return_value=Response()):
            with self.assertRaises(TimeoutError): c.flush(timeout=.03)
        self.assertEqual(c.queue[0][0],original)
        c._request=lambda *args,**kwargs:(200,{'batchId':'recovered','status':'pending'})
        self.assertTrue(c.close())
        self.assertEqual(c.queue,[])
        c.thread.join(2)

if __name__=='__main__':unittest.main()
