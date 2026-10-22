import { test } from 'node:test';
import assert from 'node:assert/strict';
import { audit } from './npm-audit.mjs';
const opts = { wait: async () => {}, log: () => {} };
test('temporary 503 retries and succeeds', async () => {
  let calls = 0;
  assert.equal(await audit({ ...opts, run: () => ++calls === 1
    ? { status: 1, stdout: '{"error":{"code":"E503"}}', stderr: '503 Service Unavailable' }
    : { status: 0 } }), 0);
  assert.equal(calls, 2);
});
test('high vulnerability report fails immediately even with a 503 advisory URL', async () => {
  let calls = 0;
  assert.equal(await audit({ ...opts, run: () => { calls++; return { status: 1,
    stdout: JSON.stringify({ metadata: { vulnerabilities: { high: 1 } }, url: 'https://example.test/503' }) }; } }), 1);
  assert.equal(calls, 1);
});
test('persistent outage remains a failure after three attempts', async () => {
  let calls = 0;
  assert.equal(await audit({ ...opts, run: () => { calls++; return { status: 1, stderr: 'ECONNRESET' }; } }), 1);
  assert.equal(calls, 3);
});
test('unexpected errors fail without retry', async () => {
  let calls = 0;
  assert.equal(await audit({ ...opts, run: () => { calls++; return { status: 1, stderr: 'invalid lockfile' }; } }), 1);
  assert.equal(calls, 1);
});
