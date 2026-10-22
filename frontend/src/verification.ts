import { reactive } from "vue";

/** Opening the dialog never sends mail; only an explicit send action does. */
export function verificationFlow(
  request: (action: string, target: string) => Promise<{ challengeId: string }>,
  errorText: (error: unknown) => string,
) {
  const verification = reactive({
    open: false,
    action: "",
    target: "",
    challengeId: "",
    code: "",
    busy: false,
    error: "",
  });
  let revision = 0;
  function openVerification(action: string, target: string) {
    revision++;
    Object.assign(verification, {
      open: true,
      action,
      target,
      challengeId: "",
      code: "",
      busy: false,
      error: "",
    });
  }
  async function sendCode() {
    if (verification.busy || !verification.open) return;
    const current = revision;
    verification.busy = true;
    verification.error = "";
    verification.code = "";
    try {
      const result = await request(verification.action, verification.target);
      if (current === revision) verification.challengeId = result.challengeId;
    } catch (error) {
      if (current === revision) verification.error = errorText(error);
    } finally {
      if (current === revision) verification.busy = false;
    }
  }
  return { verification, openVerification, sendCode };
}
