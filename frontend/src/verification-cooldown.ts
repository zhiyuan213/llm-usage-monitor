export function verificationCooldown(storage: Pick<Storage, 'getItem' | 'setItem'>, now = () => Date.now()) {
  const key = 'metertrail.verification-resend-until';
  return {
    remaining(at = now()) {
      const until = Number(storage.getItem(key));
      return Number.isFinite(until) ? Math.max(0, Math.min(60, Math.ceil((until - at) / 1000))) : 0;
    },
    start() { storage.setItem(key, String(now() + 60_000)); },
  };
}
