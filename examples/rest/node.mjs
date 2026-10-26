import { randomUUID, randomBytes } from "node:crypto";
const traceId = randomBytes(16).toString("hex");
const startedAt = new Date().toISOString();
const batch = {
  schemaVersion: "1.0",
  agent: {
    serviceName: "node-example",
    environment: "development",
    instanceId: randomUUID(),
  },
  events: [
    {
      eventId: randomUUID(),
      objectId: randomUUID(),
      type: "trace",
      revision: 1,
      traceId,
      startedAt,
      data: { name: "Example request", status: "success", durationMs: 120,
        userId: "demo-user", customerId: "demo-customer", feature: "document-analysis" },
    },
    {
      eventId: randomUUID(), objectId: randomUUID(), type: "observation", revision: 1,
      traceId, spanId: randomBytes(8).toString("hex"), startedAt,
      data: { name: "Model response", observationType: "generation", status: "success",
        durationMs: 100, provider: "example", model: "example-model",
        usage: { inputTokens: 1500, outputTokens: 400, cachedInputTokens: 500 } },
    },
  ],
};
const headers = {
  Authorization: `Bearer ${process.env.METERTRAIL_API_KEY}`,
  "Content-Type": "application/json",
};
const response = await fetch(
  `${process.env.METERTRAIL_ENDPOINT}/v1/events/batch`,
  { method: "POST", headers, body: JSON.stringify(batch) },
);
console.log(response.status, await response.json());
