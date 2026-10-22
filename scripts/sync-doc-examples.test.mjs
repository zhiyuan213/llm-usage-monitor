import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
test('REST documentation generation is identical for CRLF and LF sources', () => {
 const root = mkdtempSync(join(tmpdir(), 'metertrail-examples-'));
 const script = resolve('scripts/sync-doc-examples.mjs');
 const files = ['curl.sh','python.py','node.mjs','Send.java','main.go','csharp/Program.cs','send.php'];
 try {
  mkdirSync(join(root,'examples/rest/csharp'),{recursive:true});
  mkdirSync(join(root,'frontend/src'),{recursive:true});
  for (const file of files) writeFileSync(join(root,'examples/rest',file),'example\r\nsecond\r\n');
  execFileSync(process.execPath,[script],{cwd:root});
  const generated = readFileSync(join(root,'frontend/src/examples.ts'),'utf8');
  for (const file of files) writeFileSync(join(root,'examples/rest',file),'example\nsecond\n');
  execFileSync(process.execPath,[script,'--check'],{cwd:root});
  execFileSync(process.execPath,[script],{cwd:root});
  assert.equal(readFileSync(join(root,'frontend/src/examples.ts'),'utf8'),generated);
 } finally { rmSync(root,{recursive:true,force:true}); }
});
