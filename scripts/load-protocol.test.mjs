import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
test('k6 load probe accepts current receipts and rejects failed requests',async()=>{
 const source=(await readFile('ops/load.js','utf8')).replace(/import[^;]+;/g,'').replace('export const options=','const options=').replace('export default function()','function send()')+';send();options;';
 for(const status of [200,202,400,401,429,500]) {
  let passed,body;
  const options=vm.runInNewContext(source,{__ENV:{METERTRAIL_ENDPOINT:'http://synthetic',METERTRAIL_API_KEY:'synthetic',BATCH_SIZE:'2'},__VU:1,http:{post:(_url,raw)=>{body=JSON.parse(raw);return {status}}},check:(response,checks)=>{passed=Object.values(checks).every(check=>check(response))}});
  assert.equal(options.thresholds.checks[0],'rate==1');assert.equal(passed,status===200||status===202);assert.equal(body.events.length,2);assert.equal(body.schemaVersion,'1.0');
 }
});
