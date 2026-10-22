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
        section.rows.map((row) => {
          const prefix =
            section.en.startsWith("Body: events[]") ||
            row[0].startsWith("data.")
              ? "events[]."
              : "";
          return prefix + row[0];
        }),
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
      .find((section) => section.en === "Response: POST 200 OK")!
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
