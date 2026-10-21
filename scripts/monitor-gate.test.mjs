import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
async function gate(mode) {
 const server=createServer((req,res)=>{const q=new URL(req.url,'http://localhost').searchParams.get('query');const value=q.startsWith('up{')?'1':q.includes('histogram_quantile')?(mode==='slow'?'1':'0.1'):'0';res.setHeader('Content-Type','application/json');res.end(JSON.stringify({status:'success',data:{result:mode==='missing'?[]:[{value:[0,value]}]}}))});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 try {const child=spawn(process.execPath,['scripts/monitor-gate.mjs'],{env:{...process.env,PROMETHEUS_URL:'http://127.0.0.1:'+server.address().port},stdio:'ignore'});return await new Promise(r=>child.on('exit',r));}finally{server.close()}
}
 test('release gate accepts complete healthy evidence',async()=>assert.equal(await gate('healthy'),0));
 test('release gate rejects missing monitoring',async()=>assert.notEqual(await gate('missing'),0));
 test('release gate rejects excessive latency',async()=>assert.notEqual(await gate('slow'),0));
