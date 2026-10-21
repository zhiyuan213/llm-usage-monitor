const base = process.env.PROMETHEUS_URL;
if (!base) throw Error('PROMETHEUS_URL required');
const instance = process.env.METRICS_API_INSTANCE || 'api-blue:9090';
if (!/^[a-zA-Z0-9_.:-]+$/.test(instance)) throw Error('Invalid metrics instance');
const selector = `instance="${instance}"`;
const queries = {
  apiUp: `up{job="metertrail",${selector}}`,
  workerUp: 'up{job="metertrail",instance="worker:9090"}',
  latency: `histogram_quantile(0.95,sum by(le)(rate(http_server_requests_seconds_bucket{uri="/v1/events/batch",${selector}}[5m])))`,
  errors: `(sum(rate(http_server_requests_seconds_count{status=~"5..",${selector}}[5m])) or vector(0)) / clamp_min(sum(rate(http_server_requests_seconds_count{${selector}}[5m])),1)`,
  backlog: 'max(metertrail_oldest_pending_seconds)',
  ledger: 'max(metertrail_ledger_difference)',
  quarantine: 'max(metertrail_quarantined_batches)',
};
const results = {};
for (const [name, query] of Object.entries(queries)) {
  const response = await fetch(base + '/api/v1/query?query=' + encodeURIComponent(query), {signal:AbortSignal.timeout(5000)});
  if (!response.ok) throw Error('Monitoring unavailable');
  const data = await response.json();
  const values = data.data?.result?.map(row => Number(row.value[1]));
  if (data.status !== 'success' || !values?.length || values.some(value => !Number.isFinite(value))) throw Error('Missing or invalid metric: ' + name);
  results[name] = values;
}
if (results.apiUp.some(v=>v!==1) || results.workerUp.some(v=>v!==1) || results.latency.some(v=>v>.3) || results.errors.some(v=>v>.01) || results.backlog.some(v=>v>60) || results.ledger.some(v=>v!==0) || results.quarantine.some(v=>v!==0)) throw Error('Release gate failed: '+JSON.stringify(results));
console.log(JSON.stringify({at:new Date().toISOString(),instance,results}));
