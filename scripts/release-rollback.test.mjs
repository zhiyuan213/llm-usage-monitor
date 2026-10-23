import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, readFile, writeFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawn} from 'node:child_process';
const sha='a'.repeat(40),candidate='example.test/api@sha256:'+'b'.repeat(64),old='example.test/api@sha256:'+'c'.repeat(64);
const upstream='upstream metertrail_api { server api-blue:8080; }\n',targets='[{"targets":["api-blue:9090","worker:9090"]}]\n';
for(const mode of ['success','candidate-smoke','nginx-check','worker-start','live-smoke','observation','bad-digest','unapproved-sha'])test(`release ${mode} preserves or restores routing and worker`,async()=>{
 const dir=await mkdtemp(join(tmpdir(),'metertrail-release-'));
 try {
  for(const path of ['ops/nginx','ops/prometheus','scripts','mock-bin'])await mkdir(join(dir,path),{recursive:true});
  await writeFile(join(dir,'ops/release.sh'),await readFile('ops/release.sh'));
  await writeFile(join(dir,'ops/nginx/upstream.conf'),upstream);
  await writeFile(join(dir,'ops/prometheus/api-targets.json'),targets);
  const files={
   git:'#!/bin/sh\nprintf "%s\\n" "$RELEASE_SHA"\n',
   sleep:'#!/bin/sh\nexit 0\n',
   docker:`#!/bin/sh
printf '%s %s\\n' "\${METERTRAIL_IMAGE:-none}" "$*" >> commands.log
case "$*" in
 'inspect '*) printf '%s\\n' '${old}';;
 'compose ps -q '*) echo old-container;;
 'compose exec -T web nginx -t') if [ "$MODE" = nginx-check ] && [ ! -e nginx-failed ]; then touch nginx-failed; exit 1; fi;;
 'compose up -d --no-deps --wait worker') if [ "$MODE" = worker-start ]; then exit 1; fi;;
esac
`,
   node:`#!/bin/sh
printf '%s %s\\n' "\${METERTRAIL_ENDPOINT:-none}" "$*" >> commands.log
if [ "$1" = scripts/release-smoke.mjs ]; then
 if [ "$MODE" = candidate-smoke ] && [ "$METERTRAIL_ENDPOINT" = http://candidate ]; then exit 1; fi
 if [ "$MODE" = live-smoke ] && [ -e live-seen ]; then :; elif [ "$MODE" = live-smoke ] && [ "$METERTRAIL_ENDPOINT" = http://live ]; then touch live-seen; exit 1; fi
fi
if [ "$1" = scripts/monitor-gate.mjs ]; then
 count=0; [ ! -e gates ] || count=$(cat gates); count=$((count+1)); echo "$count" > gates
 if [ "$MODE" = observation ] && [ "$count" -gt 1 ]; then exit 1; fi
fi
echo '{"fixture":true}'
`};
  for(const [name,body] of Object.entries(files))await writeFile(join(dir,'mock-bin',name),body,{mode:0o755});
  const env={...process.env,MODE:mode,RELEASE_SHA:sha,APPROVED_SHA:sha,CANDIDATE_IMAGE:candidate,SMOKE_API_KEY:'synthetic',PROMETHEUS_URL:'http://metrics',CANDIDATE_ENDPOINT:'http://candidate',SMOKE_ENDPOINT:'http://live'};
  if(mode==='bad-digest')env.CANDIDATE_IMAGE='example.test/api:latest';
  if(mode==='unapproved-sha')env.APPROVED_SHA='d'.repeat(40);
  const bash=process.platform==='win32'?'D:/Program Files/Git/bin/bash.exe':'bash';
  const child=spawn(bash,['-c','export PATH="$PWD/mock-bin:$PATH"; bash ops/release.sh'],{cwd:dir,env,stdio:['ignore','pipe','pipe']});
  let output='';child.stdout.on('data',x=>output+=x);child.stderr.on('data',x=>output+=x);
  const code=await new Promise((resolve,reject)=>{const timeout=setTimeout(()=>{child.kill();reject(Error('release fixture timed out'))},15000);child.on('error',reject);child.on('exit',code=>{clearTimeout(timeout);resolve(code)})});
  assert.equal(code===0,mode==='success',output);
  const routing=await readFile(join(dir,'ops/nginx/upstream.conf'),'utf8'),monitoring=await readFile(join(dir,'ops/prometheus/api-targets.json'),'utf8');
  const commands=await readFile(join(dir,'commands.log'),'utf8').catch(error=>{if(error.code==='ENOENT')return '';throw error});
  if(mode==='success'){assert.match(routing,/api-green:8080/);assert.match(monitoring,/api-green:9090/)}
  else {assert.equal(routing,upstream);assert.equal(monitoring,targets);if(['nginx-check','worker-start','live-smoke','observation'].includes(mode))assert.ok(commands.includes(old+' compose up -d --no-deps worker'),commands)}
 } finally {await rm(dir,{recursive:true,force:true})}
});
