import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, delimiter } from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawn } from 'node:child_process';
import { sources, batches } from './sync-scenario-examples.mjs';
const root=resolve('.');
const folder=await mkdtemp(join(tmpdir(),'metertrail-scenarios-'));
let received=[];
const server=createServer(async(req,res)=>{
  const chunks=[];for await(const chunk of req)chunks.push(chunk);
  try {
    assert.equal(req.url,'/v1/events/batch');assert.equal(req.headers.authorization,'Bearer synthetic');
    const body=JSON.parse(Buffer.concat(chunks));
    for(const event of body.events) {
      assert.equal(event.eventId.length,36); assert.equal(event.revision,1);
      assert.ok(Math.abs(Date.now()-Date.parse(event.startedAt))<60000);
      if(event.type!=='score')assert.match(event.traceId,/^[a-f0-9]{32}$/);
      if(event.data.usage) {
        for(const key of ['inputTokens','outputTokens','cachedInputTokens'])assert.ok(Object.hasOwn(event.data.usage,key),'Missing usage field: '+key);
        assert.equal(event.data.usage.cachedInputTokens,0);
      }
    }
    received.push(body);
    res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({batchId:'00000000-0000-4000-8000-000000000001',received:body.events.length,status:'pending'}));
  } catch(error) { res.writeHead(400,{'Content-Type':'application/json'});res.end(JSON.stringify({code:String(error)})); }
});
async function run(command,args,env) {
  return new Promise((resolve,reject)=>{
    const child=spawn(command,args,{cwd:folder,env});let output='';
    child.stdout.on('data',d=>output+=d);child.stderr.on('data',d=>output+=d);
    const timer=setTimeout(()=>{child.kill();reject(Error('Scenario timed out: '+output));},45000);
    child.on('error',e=>{clearTimeout(timer);reject(e)});
    child.on('exit',code=>{clearTimeout(timer);code===0?resolve(output):reject(Error(output))});
  });
}
try {
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const dependencies=(await readFile('sdk/java/target/scenario-classpath.txt','utf8')).trim();
  const classpath=resolve('sdk/java/target/classes')+delimiter+dependencies;
  let lastTrace;
  for(const language of ['Node.js','Python','Java']) for(const kind of ['single','multiple','score','score-trace']) {
    received=[];
    let code=sources[language][kind];
    let command,args;
    if(language==='Node.js') {code=code.replace('./node/index.mjs',pathToFileURL(resolve('sdk/node/index.mjs')).href);await writeFile(join(folder,'scenario.mjs'),code);command=process.execPath;args=['scenario.mjs'];}
    else if(language==='Python') {await writeFile(join(folder,'scenario.py'),code);command=process.env.PYTHON||'python3';args=['scenario.py'];}
    else {await writeFile(join(folder,'Scenario.java'),code);command=process.env.JAVA||'java';args=['--class-path',classpath,'Scenario.java'];}
    const output=await run(command,args,{...process.env,PYTHONPATH:resolve('sdk'),METERTRAIL_ENDPOINT:`http://127.0.0.1:${server.address().port}`,METERTRAIL_API_KEY:'synthetic',METERTRAIL_TRACE_ID:lastTrace||''});
    assert.deepEqual(received.map(b=>b.events.length),kind==='single'?[2]:kind==='multiple'?[1,1,2]:[1],output);
    const events=received.flatMap(b=>b.events);
    assert.equal(new Set(events.map(e=>e.eventId)).size,events.length);
    if(kind.startsWith('score')) {
      assert.equal(events[0].data.targetId,kind==='score-trace'?`trace-${lastTrace}`:`obs-${lastTrace}-1`);
      assert.equal(events[0].data.targetType,kind==='score-trace'?'trace':'observation');
    } else {
      lastTrace=events[0].traceId;
      assert.equal(new Set(events.map(e=>e.traceId)).size,1);
      assert.equal(new Set(events.map(e=>e.objectId)).size,events.length);
      assert.equal(events.filter(e=>e.type==='trace').length,1);
      assert.ok(events.filter(e=>e.type==='observation').every(e=>e.data.usage.inputTokens>0));
    }
    // Compare every published event (besides SDK-generated IDs/defaults) to canonical REST content.
    const expected=batches(kind,lastTrace,events[0].startedAt).flat();
    events.forEach((e,i)=>{const {eventId,revision,...rest}=e; if(kind.startsWith('score')) expected[i].objectId=e.objectId; assert.deepEqual(rest,expected[i]);});
    console.log(`PASS ${language} ${kind}: ${received.length} batch(es), ${events.length} events`);
  }
} finally { await new Promise(r=>server.close(r)); await rm(folder,{recursive:true,force:true}); }
