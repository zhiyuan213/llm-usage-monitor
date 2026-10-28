import { randomUUID, randomBytes } from 'node:crypto';
import { pathToFileURL } from 'node:url';

/** Reuse a client in your backend. sendBatch returns an acceptance receipt. */
export class MeterTrailClient {
  constructor({ endpoint, apiKey }) {
    if (!endpoint || !apiKey) throw new Error('endpoint and apiKey are required');
    this.endpoint = endpoint.replace(/\/$/, '');
    this.apiKey = apiKey;
  }

  async sendBatch(batch) {
    const response = await fetch(`${this.endpoint}/v1/events/batch`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${this.apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(batch),
      signal: AbortSignal.timeout(10_000),
    });
    if (response.status !== 202) {
      throw new Error(`Upload failed: HTTP ${response.status} ${await response.text()}`);
    }
    return response.json();
  }
}

export function createSampleBatch() {
  const traceId = randomBytes(16).toString('hex');
  const startedAt = new Date().toISOString(); // Use the measured request start.
  const trace = {
    eventId: randomUUID(), revision: 1, type: 'trace',
    objectId: `trace-${traceId}`, traceId, startedAt,
    data: {
      name: 'Document analysis', status: 'success', durationMs: 2100,
      userId: 'demo-user', customerId: 'demo-customer', feature: 'document-analysis',
    },
  };
  const observation = {
    eventId: randomUUID(), revision: 1, type: 'observation',
    objectId: `obs-${traceId}-1`, traceId,
    spanId: randomBytes(8).toString('hex'), startedAt,
    data: {
      name: 'Model response', observationType: 'generation', status: 'success',
      durationMs: 2000, provider: 'example', model: 'example-model',
      usage: { inputTokens: 1500, outputTokens: 400, cachedInputTokens: 0 },
    },
  };
  return {
    schemaVersion: '1.0',
    agent: { serviceName: 'document-api', environment: 'test', instanceId: 'demo-1' },
    events: [trace, observation],
  };
}

async function main() {
  const client = new MeterTrailClient({
    endpoint: process.env.METERTRAIL_ENDPOINT,
    apiKey: process.env.METERTRAIL_API_KEY,
  });
  const batch = createSampleBatch();
  // Retry with this batch unchanged; do not regenerate IDs after a timeout.
  const receipt = await client.sendBatch(batch);
  console.log(202, receipt);
}

// Import the class and factory without automatically sending an example.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
