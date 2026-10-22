import { expect, it } from "vitest";
import {
  batchIdExample,
  queryUrl,
  queryMethod,
  queryHeaders,
  queryResult,
  queryStates,
  queryFields,
} from "./batch-query-reference";
import { minimalReceiptExample } from "./protocol-reference";
it("uses the receipt batch ID in the explicit authenticated GET path", () => {
  expect(queryMethod).toBe("GET");
  expect(batchIdExample).toBe(JSON.parse(minimalReceiptExample).batchId);
  expect(new URL(queryUrl.replace("{batchId}", batchIdExample)).pathname).toBe(
    "/v1/batches/" + batchIdExample,
  );
  expect(queryHeaders).toContain("Authorization: Bearer lm_live_");
  expect(queryHeaders).toContain("Host: metertrail.com");
});
it("matches the existing query response fields and documented state codes", () => {
  expect(Object.keys(queryResult).sort()).toEqual(
    [
      "status",
      "received",
      "accepted",
      "duplicates",
      "rejected",
      "errors",
    ].sort(),
  );
  expect(
    queryResult.accepted + queryResult.duplicates + queryResult.rejected,
  ).toBe(queryResult.received);
  expect(queryStates.map((row) => row[0])).toEqual([
    "pending",
    "completed",
    "quarantined",
    "failed",
    "deleted",
  ]);
});

it("documents every query response field", () => {
  for (const field of Object.keys(queryResult))
    expect(queryFields.some((row) => row[0] === field)).toBe(true);
});
