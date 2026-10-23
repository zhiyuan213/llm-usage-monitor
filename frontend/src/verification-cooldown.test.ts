import { expect, it } from 'vitest';
import { verificationCooldown } from './verification-cooldown';
it('shares successful-send cooldown across routes and reloads, and expires without sliding', () => {
 const values = new Map<string,string>();
 const storage = { getItem: (k:string) => values.get(k) ?? null, setItem: (k:string,v:string) => { values.set(k,v); } };
 let now = 1000;
 const first = verificationCooldown(storage, () => now);
 expect(first.remaining()).toBe(0);
 first.start(); expect(first.remaining()).toBe(60);
 now += 12500;
 const reloaded = verificationCooldown(storage, () => now);
 expect(reloaded.remaining()).toBe(48);
 now += 47500; expect(reloaded.remaining()).toBe(0);
});
it('ignores invalid stored deadlines', () => {
 const storage = { getItem: () => 'invalid', setItem: () => {} };
 expect(verificationCooldown(storage).remaining()).toBe(0);
});
