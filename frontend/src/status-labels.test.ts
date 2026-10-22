import { expect, it } from "vitest";
import { batchStatusLabel } from "./status-labels";

it("translates platform batch states while preserving English codes and unknown values", () => {
  const states = {
    pending: "待处理",
    completed: "已完成",
    quarantined: "已隔离",
    failed: "已失败",
    deleted: "已删除",
  };
  for (const [value, label] of Object.entries(states)) {
    expect(batchStatusLabel(value, (_en, zh) => zh)).toBe(label);
    expect(batchStatusLabel(value, (en) => en)).toBe(value);
  }
  expect(batchStatusLabel("new-state", (_en, zh) => zh)).toBe("new-state");
});
