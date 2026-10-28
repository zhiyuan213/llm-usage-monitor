import { expect, it } from "vitest";
import {
  sections,
  requestHeaders,
  requestUrl,
  requestMethod,
  bodyExample,
  minimalBody,
  receiptExample,
  minimalReceiptExample,
} from "./protocol-reference";
function paths(value: unknown, prefix = ""): string[] {
  if (Array.isArray(value))
    return value.flatMap((item) => paths(item, prefix + "[]"));
  if (!value || typeof value !== "object") return [];
  return Object.entries(value).flatMap(([key, child]) => {
    const path = prefix ? prefix + "." + key : key;
    return [path, ...paths(child, path)];
  });
}
it('starts with an attributed request and linked model usage, not an empty trace', () => {
  const basic = JSON.parse(minimalBody);
  expect(basic.schemaVersion).toBeUndefined();
  expect(basic.agent).toBeUndefined();
  const [trace, generation] = JSON.parse(minimalBody).events;
  expect(trace.revision).toBeUndefined();
  expect(generation.revision).toBeUndefined();
  expect(trace.data.userId).toBe('demo-user');
  expect(trace.data.customerId).toBe('demo-customer');
  expect(trace.data.feature).toBe('document-analysis');
  expect(generation.type).toBe('observation');
  expect(generation.traceId).toBe(trace.traceId);
  expect(generation.data.observationType).toBe('generation');
  expect(generation.data.model).toBe('example-model');
  expect(generation.data.usage.inputTokens + generation.data.usage.outputTokens).toBe(1900);
  expect(JSON.parse(minimalReceiptExample).received).toBe(2);
});
it("documents every example header and derives Host from the declared URL", () => {
  const documented = new Set(sections[0].rows.map((row) => row[0]));
  for (const line of requestHeaders.split("\n"))
    expect(documented.has(line.split(":")[0])).toBe(true);
  expect(requestHeaders).toContain("Host: " + new URL(requestUrl).host);
  expect(sections[0].rows.find((row) => row[0] === "Host")![5]).toContain(
    "自动生成",
  );
  expect(requestMethod).toBe("POST");
});
it("documents every field in both minimal and full request examples", () => {
  const documented = new Set(
    sections
      .filter((section) => section.en.startsWith("Body:"))
      .flatMap((section) =>
          section.rows.map((row) => row[0].replaceAll("[n]", "[]")),
      ),
  );
  for (const example of [minimalBody, bodyExample]) {
    for (const field of paths(JSON.parse(example)))
      expect(documented.has(field), field).toBe(true);
  }
});
it("documents response examples and keeps counts consistent with their request", () => {
  const receiptFields = new Set(
    sections
      .find((section) => section.en === "Response: POST 202 Accepted")!
      .rows.map((row) => row[0]),
  );
  expect(JSON.parse(minimalReceiptExample).received).toBe(
    JSON.parse(minimalBody).events.length,
  );
  for (const example of [receiptExample, minimalReceiptExample]) {
    for (const field of paths(JSON.parse(example)))
      expect(receiptFields.has(field), field).toBe(true);
  }
  expect(JSON.parse(receiptExample).received).toBe(
    JSON.parse(bodyExample).events.length,
  );
});
