import { expect, it, vi } from "vitest";
import { contextLoader } from "./context-loader";

it("shares concurrent requests and refreshes on expiry or explicit refresh", async () => {
  let now = 100;
  const clock = vi.spyOn(Date, "now").mockImplementation(() => now);
  try {
    const load = vi.fn(async () => ({ revision: load.mock.calls.length }));
    const context = contextLoader(load, 30_000);
    const first = context();
    expect(context()).toBe(first);
    expect(context(true)).toBe(first);
    await first;
    await context();
    expect(load).toHaveBeenCalledTimes(1);
    await context(true);
    expect(load).toHaveBeenCalledTimes(2);
    now += 30_000;
    await context();
    expect(load).toHaveBeenCalledTimes(3);
  } finally {
    clock.mockRestore();
  }
});

it("does not cache failed loads or leak context between console instances", async () => {
  const load = vi
    .fn()
    .mockRejectedValueOnce(new Error("offline"))
    .mockResolvedValue("user A");
  const context = contextLoader(load);
  await expect(context()).rejects.toThrow("offline");
  await expect(context()).resolves.toBe("user A");
  await expect(contextLoader(async () => "user B")()).resolves.toBe("user B");
  expect(load).toHaveBeenCalledTimes(2);
});
