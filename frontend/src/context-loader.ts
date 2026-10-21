// Each console instance owns its context; nothing is shared between logins.
export function contextLoader<T>(load: () => Promise<T>, ttl = 30_000) {
  let cached: T;
  let expires = 0;
  let pending: Promise<T> | undefined;
  return (force = false): Promise<T> => {
    if (pending) return pending;
    if (!force && Date.now() < expires) return Promise.resolve(cached);
    pending = load()
      .then((value) => {
        cached = value;
        expires = Date.now() + ttl;
        return value;
      })
      .finally(() => {
        pending = undefined;
      });
    return pending;
  };
}
