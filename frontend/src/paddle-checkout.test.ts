import { beforeEach, expect, it, vi } from "vitest";
import { checkoutTransaction, validateCheckoutConfig } from "./paddle-checkout";
it("accepts only Paddle transaction IDs in payment links", () => {
  expect(checkoutTransaction("?_ptxn=txn_" + "a".repeat(26))).toBe(
    "txn_" + "a".repeat(26),
  );
  for (const query of [
    "",
    "?_ptxn=javascript:alert(1)",
    "?_ptxn=sub_" + "a".repeat(26),
  ])
    expect(checkoutTransaction(query)).toBeNull();
});
it("refuses disabled or mixed sandbox/live credentials", () => {
  expect(() =>
    validateCheckoutConfig({
      enabled: true,
      environment: "sandbox",
      token: "test_example",
    }),
  ).not.toThrow();
  expect(() =>
    validateCheckoutConfig({
      enabled: true,
      environment: "production",
      token: "test_example",
    }),
  ).toThrow();
  expect(() =>
    validateCheckoutConfig({
      enabled: false,
      environment: "sandbox",
      token: "test_example",
    }),
  ).toThrow();
});
beforeEach(() => vi.resetModules());
it("initializes sandbox before checkout and never activates a plan from client events", async () => {
  const paddle = {
    Environment: { set: vi.fn() },
    Initialize: vi.fn(),
    Checkout: { open: vi.fn(), close: vi.fn() },
  };
  vi.stubGlobal("window", { Paddle: paddle });
  const { startCheckout } = await import("./paddle-checkout");
  const callback = vi.fn();
  const config = {
    enabled: true,
    environment: "sandbox" as const,
    token: "test_example",
  };
  const stop = await startCheckout(
    config,
    "txn_" + "a".repeat(26),
    "zh-Hans",
    callback,
  );
  expect(paddle.Environment.set).toHaveBeenCalledWith("sandbox");
  expect(paddle.Environment.set.mock.invocationCallOrder[0]).toBeLessThan(
    paddle.Initialize.mock.invocationCallOrder[0],
  );
  expect(paddle.Checkout.open).not.toHaveBeenCalled();
  const options = paddle.Initialize.mock.calls[0][0];
  expect(options.token).toBe("test_example");
  options.eventCallback({ name: "checkout.completed" });
  expect(callback).toHaveBeenCalledWith({ name: "checkout.completed" });
  stop();
  expect(paddle.Checkout.close).toHaveBeenCalledOnce();
  options.eventCallback({ name: "checkout.completed" });
  expect(callback).toHaveBeenCalledTimes(1);
});
