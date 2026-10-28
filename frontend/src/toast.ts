import { customRef, getCurrentScope, onScopeDispose, shallowRef } from 'vue';

type ToastKind = 'success' | 'error' | 'info';
export const toast = shallowRef<{ message: string; kind: ToastKind; owner?: symbol } | null>(null);
let timer: ReturnType<typeof setTimeout> | undefined;

export function dismissToast() {
  if (timer) clearTimeout(timer);
  timer = undefined;
  toast.value = null;
}

export function showToast(message: string, kind: ToastKind = 'success', owner?: symbol) {
  dismissToast();
  if (!message.trim()) return;
  toast.value = { message, kind, owner };
  timer = setTimeout(dismissToast, 1000);
}

// Preserve existing notice assignment/append behavior, including repeated identical messages.
export function useToastNotice(kind: ToastKind = 'success') {
  const owner = Symbol('notice');
  let value = '';
  if (getCurrentScope()) onScopeDispose(() => {
    if (toast.value?.owner === owner) dismissToast();
  });
  return customRef<string>((track, trigger) => ({
    get() { track(); return value; },
    set(message) {
      value = message;
      if (message) showToast(message, kind, owner);
      else if (toast.value?.owner === owner) dismissToast();
      trigger();
    },
  }));
}
