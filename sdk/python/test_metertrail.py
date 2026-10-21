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

    def test_202_is_polled(self):
        results=[]
        c=self.client(on_result=results.append)
        c.record({'type':'trace','objectId':'one','data':{}})
        seen=[]
        def request(path,body=None,deadline=None):
            seen.append(path)
            return (202,{'batchId':'one'}) if body else (200,{'status':'completed','accepted':1,'errors':[]})
        c._request=request
        c.paused=None
        self.assertTrue(c.flush())
        self.assertEqual(seen,['/v1/events/batch','/v1/batches/one'])
        self.assertEqual(len(results),1)
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

if __name__=='__main__':unittest.main()
