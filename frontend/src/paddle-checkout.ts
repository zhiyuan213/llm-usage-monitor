export interface CheckoutConfig {
  enabled: boolean;
  environment: "sandbox" | "production";
  token: string;
}
export interface PaddleEvent {
  name: string;
}
interface PaddleClient {
  Environment: { set(value: "sandbox"): void };
  Initialize(options: Record<string, unknown>): void;
  Checkout: { open(options: { transactionId: string }): void; close(): void };
}
declare global {
  interface Window {
    Paddle?: PaddleClient;
  }
}
export const paddleScriptUrl = "https://cdn.paddle.com/paddle/v2/paddle.js";
let loading: Promise<PaddleClient> | undefined;
let initialized = false;
let listener: ((event: PaddleEvent) => void) | undefined;
export function checkoutTransaction(search: string): string | null {
  const id = new URLSearchParams(search).get("_ptxn");
  return id && /^txn_[a-z0-9]{26}$/.test(id) ? id : null;
}
export function validateCheckoutConfig(config: CheckoutConfig): void {
  const prefix = config.environment === "sandbox" ? "test_" : "live_";
  if (
    !config.enabled ||
    !["sandbox", "production"].includes(config.environment) ||
    !config.token?.startsWith(prefix)
  )
    throw new Error("PAYMENTS_NOT_CONFIGURED");
}
function loadPaddle(): Promise<PaddleClient> {
  if (window.Paddle) return Promise.resolve(window.Paddle);
  if (loading) return loading;
  loading = new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = paddleScriptUrl;
    script.async = true;
    const timeout = setTimeout(() => fail(), 15000);
    function fail() {
      clearTimeout(timeout);
      script.remove();
      loading = undefined;
      reject(new Error("CHECKOUT_UNAVAILABLE"));
    }
    script.onerror = fail;
    script.onload = () => {
      clearTimeout(timeout);
      if (window.Paddle) resolve(window.Paddle);
      else fail();
    };
    document.head.appendChild(script);
  });
  return loading;
}
export async function startCheckout(
  config: CheckoutConfig,
  transactionId: string,
  locale: string,
  onEvent: (event: PaddleEvent) => void,
): Promise<() => void> {
  validateCheckoutConfig(config);
  if (!/^txn_[a-z0-9]{26}$/.test(transactionId))
    throw new Error("INVALID_REQUEST");
  const paddle = await loadPaddle();
  listener = onEvent;
  if (!initialized) {
    if (config.environment === "sandbox") paddle.Environment.set("sandbox");
    paddle.Initialize({
      token: config.token,
      checkout: {
        settings: { displayMode: "overlay", theme: "light", locale },
      },
      eventCallback: (event: PaddleEvent) => listener?.(event),
    });
    // Paddle automatically opens the _ptxn transaction on this payment-link page.
    initialized = true;
  } else {
    paddle.Checkout.open({ transactionId });
  }
  return () => {
    if (listener === onEvent) {
      listener = undefined;
      paddle.Checkout.close();
    }
  };
}
