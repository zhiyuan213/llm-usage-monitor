import { expect, it, vi } from "vitest";
import { verificationFlow } from "./verification";

it("does not send on open or reopen and sends the current action only on request", async () => {
  const request = vi.fn(async () => ({ challengeId: "challenge-1" }));
  const flow = verificationFlow(request, String);
  flow.openVerification("key.create", "project:key-name");
  expect(request).not.toHaveBeenCalled();
  expect(flow.verification.challengeId).toBe("");
  await flow.sendCode();
  expect(request).toHaveBeenCalledExactlyOnceWith(
    "key.create",
    "project:key-name",
  );
  expect(flow.verification.challengeId).toBe("challenge-1");
  flow.verification.open = false;
  flow.openVerification("key.revoke", "project:key-id");
  expect(request).toHaveBeenCalledTimes(1);
  expect(flow.verification.challengeId).toBe("");
  await flow.sendCode();
  expect(request).toHaveBeenLastCalledWith("key.revoke", "project:key-id");
});

it("blocks duplicate sends and ignores responses from a previously opened action", async () => {
  let resolve!: (result: { challengeId: string }) => void;
  const request = vi.fn(
    () =>
      new Promise<{ challengeId: string }>((done) => {
        resolve = done;
      }),
  );
  const flow = verificationFlow(request, String);
  flow.openVerification("key.create", "old");
  const pending = flow.sendCode();
  await flow.sendCode();
  expect(request).toHaveBeenCalledTimes(1);
  flow.openVerification("project.delete", "new");
  resolve({ challengeId: "stale" });
  await pending;
  expect(flow.verification.challengeId).toBe("");
  expect(flow.verification.target).toBe("new");
  expect(flow.verification.busy).toBe(false);
});

it("shows send failures and clears the error after a successful manual retry", async () => {
  const request = vi
    .fn()
    .mockRejectedValueOnce(new Error("RATE_LIMITED"))
    .mockResolvedValueOnce({ challengeId: "retry" });
  const flow = verificationFlow(request, (error) => (error as Error).message);
  flow.openVerification("key.create", "project:key-name");
  await flow.sendCode();
  expect(flow.verification.error).toBe("RATE_LIMITED");
  expect(flow.verification.challengeId).toBe("");
  expect(flow.verification.busy).toBe(false);
  await flow.sendCode();
  expect(flow.verification.error).toBe("");
  expect(flow.verification.challengeId).toBe("retry");
});
