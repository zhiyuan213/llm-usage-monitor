// Run the exact published examples against a local capture server; no real keys or units.
import { createServer as viteServer } from 'vite';
import { createServer } from 'node:http';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
const vite = await viteServer({server:{middlewareMode:true},appType:'custom'});
const directory = await mkdtemp(join(tmpdir(),'metertrail-tutorials-'));
const received=[];
const server=createServer(async (req,res)=>{
  let raw=''; for await(const chunk of req) raw+=chunk;
  try {
    assert.equal(req.url,'/v1/events/batch'); assert.equal(req.method,'POST');
    assert.equal(req.headers.authorization,'Bearer example');
    const body=JSON.parse(raw); assert.equal(body.schemaVersion,undefined);
    assert.equal(body.agent,undefined); assert.equal(body.events.length,2);
    const event=body.events[0]; assert.equal(event.type,'trace'); assert.equal(event.revision,undefined);
    assert.match(event.traceId,/^[a-f0-9]{32}$/); assert.match(event.eventId,/^[a-f0-9-]{36}$/);
    assert.ok(Math.abs(Date.now()-Date.parse(event.startedAt))<60000); assert.equal(event.data.name,'Example request');
    assert.equal(event.data.userId,'demo-user'); assert.equal(event.data.customerId,'demo-customer');
    assert.equal(event.data.feature,'document-analysis');
    const model=body.events[1]; assert.equal(model.type,'observation');
    assert.equal(model.traceId,event.traceId); assert.notEqual(model.objectId,event.objectId);
    assert.notEqual(model.eventId,event.eventId); assert.equal(model.revision,undefined);
    assert.equal(model.data.observationType,'generation');
    assert.equal(model.data.provider,'example'); assert.equal(model.data.model,'example-model');
    assert.deepEqual(model.data.usage,{inputTokens:1500,outputTokens:400,cachedInputTokens:500});
    received.push(body); res.writeHead(200,{'Content-Type':'application/json'}); res.end(JSON.stringify({batchId:'example',received:2,status:'pending'}));
  } catch(e) {res.writeHead(400); res.end(String(e));}
});
function run(command,args,env) {
  return new Promise((resolve,reject)=>{
    const child=spawn(command,args,{cwd:directory,env,stdio:['ignore','pipe','pipe']});
    let output='';child.stdout.on('data',c=>output+=c);child.stderr.on('data',c=>output+=c);
    const timeout=setTimeout(()=>{child.kill();reject(Error('Tutorial timed out'));},15000);
    child.on('error',e=>{clearTimeout(timeout);reject(e);});
    child.on('exit',code=>{clearTimeout(timeout);code===0&&output.includes('pending')?resolve(output):reject(Error(`Exit ${code}: ${output}`));});
  });
}
try {
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const {tutorials}=await vite.ssrLoadModule('/src/tutorial-content.ts');
  const env={...process.env,METERTRAIL_ENDPOINT:`http://127.0.0.1:${server.address().port}`,METERTRAIL_API_KEY:'example'};
  for (const tutorial of tutorials) {
    await writeFile(join(directory,tutorial.filename),tutorial.code);
    const command=tutorial.slug==='nodejs'?process.execPath:tutorial.slug==='python'?(process.env.PYTHON||'python3'):tutorial.slug==='java'?(process.env.JAVA||'java'):(process.env.BASH||'sh');
    // REST's embedded generator invokes python3; callers must provide it in PATH.
    await run(command,[tutorial.filename],env);
    assert.equal(received.length,tutorials.indexOf(tutorial)+1);
    console.log(`PASS: ${tutorial.slug} published code returned HTTP 200 / pending`);
  }
  assert.equal(new Set(received.map(b=>b.events[0].eventId)).size,tutorials.length);
} finally { await vite.close(); await new Promise(resolve=>server.close(resolve)); await rm(directory,{recursive:true,force:true}); }
