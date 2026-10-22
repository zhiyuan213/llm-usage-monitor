import { spawnSync } from 'node:child_process';
import { setTimeout } from 'node:timers/promises';
import { pathToFileURL } from 'node:url';

export async function audit({ run, wait = setTimeout, log = console.error }) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    const result = run();
    const output = `${result.stdout || ''}\n${result.stderr || ''}`;
    log(output);
    if (result.status === 0) return 0;
    // A valid vulnerability report must never be treated as service downtime.
    let report;
    try { report = JSON.parse(result.stdout); } catch {}
    if (report?.metadata?.vulnerabilities) return 1;
    const transient = /\b(429|500|502|503|504)\b|EAI_AGAIN|ECONNRESET|ETIMEDOUT|ECONNREFUSED|ENOTFOUND|timed? out|Service Unavailable/i.test(output)
      || result.error?.code === 'ETIMEDOUT';
    if (!transient || attempt === 3) return 1;
    log(`Audit service unavailable; retry ${attempt + 1}/3 in ${attempt * 5}s.`);
    await wait(attempt * 5000);
  }
  return 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await audit({ run: () => spawnSync('npm', [
    'audit', '--audit-level=high', '--json', '--fetch-retries=0', '--fetch-timeout=20000',
  ], { cwd: new URL('../frontend/', import.meta.url), encoding: 'utf8', timeout: 30000 }) });
}
