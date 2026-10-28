import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { effectScope } from 'vue';
import { dismissToast, showToast, toast, useToastNotice } from './toast';

beforeEach(() => { vi.useFakeTimers(); dismissToast(); });
afterEach(() => { dismissToast(); vi.useRealTimers(); });

it('dismisses after exactly one second and resets the clock for repeated feedback', () => {
  showToast('ID copied');
  vi.advanceTimersByTime(900);
  expect(toast.value?.message).toBe('ID copied');
  showToast('ID copied');
  vi.advanceTimersByTime(999);
  expect(toast.value?.message).toBe('ID copied');
  vi.advanceTimersByTime(1);
  expect(toast.value).toBeNull();
});

it('preserves notice appends and cleans up only the current page owner', () => {
  const first = effectScope(), second = effectScope();
  const a = first.run(() => useToastNotice())!, b = second.run(() => useToastNotice())!;
  a.value = 'Refund confirmed';
  a.value += ' · Cancellation pending';
  expect(toast.value?.message).toBe('Refund confirmed · Cancellation pending');
  b.value = 'Saved';
  first.stop();
  expect(toast.value?.message).toBe('Saved');
  second.stop();
  expect(toast.value).toBeNull();
  vi.advanceTimersByTime(1000);
  expect(toast.value).toBeNull();
});
