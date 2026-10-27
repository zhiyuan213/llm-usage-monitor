import { randomUUID, randomBytes } from "node:crypto";
const traceId = randomBytes(16).toString("hex");
const startedAt = new Date().toISOString();
const batch = {
  events: [
    {
      eventId: randomUUID(),
      objectId: randomUUID(),
      type: "trace",
      traceId,
      startedAt,
      data: { name: "Document analysis", status: "success", durationMs: 2100,
        userId: "demo-user", customerId: "demo-customer", feature: "document-analysis" },
    },
    {
      eventId: randomUUID(), objectId: randomUUID(), type: "observation",
      traceId, spanId: randomBytes(8).toString("hex"), startedAt,
      data: { name: "Model response", observationType: "generation", status: "success",
        durationMs: 2000, provider: "example", model: "example-model",
        usage: { inputTokens: 1500, outputTokens: 400 } },
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
