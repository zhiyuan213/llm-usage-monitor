<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from "vue";
import { language, t } from "./lib";
import {
  checkoutTransaction,
  startCheckout,
  type CheckoutConfig,
} from "./paddle-checkout";
const state = ref("loading");
const sandbox = ref(false);
let active = true;
let close: (() => void) | undefined;
function retry() {
  window.location.reload();
}
onBeforeUnmount(() => {
  active = false;
  close?.();
});
onMounted(async () => {
  const transaction = checkoutTransaction(window.location.search);
  if (!transaction) {
    state.value = "missing";
    return;
  }
  try {
    const response = await fetch("/v1/checkout/config", {
      cache: "no-store",
      signal: AbortSignal.timeout(15000),
    });
    if (!response.ok) throw new Error("CHECKOUT_UNAVAILABLE");
    const config: CheckoutConfig = await response.json();
    if (!active) return;
    sandbox.value = config.environment === "sandbox";
    close = await startCheckout(
      config,
      transaction,
      language.value === "zh-CN" ? "zh-Hans" : "en",
      (event) => {
        if (!active) return;
        if (event.name === "checkout.completed") state.value = "completed";
        else if (
          event.name === "checkout.closed" &&
          state.value !== "completed"
        )
          state.value = "closed";
        else if (event.name === "checkout.error") state.value = "error";
      },
    );
    if (!active) {
      close();
      return;
    }
    if (state.value === "loading") state.value = "open";
  } catch {
    if (active) state.value = "error";
  }
});
</script>
<template>
  <section class="panel checkout-card" aria-live="polite">
    <span v-if="sandbox" class="notice">{{
      t("Sandbox · No real payment", "沙箱测试 · 不产生真实扣款")
    }}</span>
    <h1>{{ t("Secure checkout", "安全支付") }}</h1>
    <p v-if="state === 'loading'">
      {{ t("Opening checkout…", "正在打开收银台…") }}
    </p>
    <p v-else-if="state === 'missing'">
      {{
        t(
          "Choose a plan in Billing to start checkout.",
          "请从套餐与账单页面选择套餐，发起付款。",
        )
      }}
    </p>
    <p v-else-if="state === 'completed'">
      {{
        t(
          "Checkout completed. Your plan will update after the payment notification is verified. Check Billing for the latest status.",
          "收银台流程已完成。套餐将在付款通知验证通过后更新，请到套餐与账单查看最新状态。",
        )
      }}
    </p>
    <p v-else-if="state === 'error'" class="notice error" role="alert">
      {{
        t(
          "Checkout could not be opened. Please retry or contact support.",
          "收银台打开失败，请重试或联系支持。",
        )
      }}
    </p>
    <p v-else-if="state === 'closed'">
      {{
        t(
          "Checkout closed. You can reopen it to continue.",
          "收银台已关闭，可以重新打开继续付款。",
        )
      }}
    </p>
    <p v-else>
      {{
        t(
          "Complete payment in the Paddle checkout window.",
          "请在 Paddle 收银台窗口中完成付款。",
        )
      }}
    </p>
    <div class="checkout-actions">
      <button
        v-if="['error', 'closed'].includes(state)"
        class="btn primary"
        @click="retry"
      >
        {{ t("Retry", "重新打开") }}
      </button>
      <RouterLink class="btn" to="/app/billing">{{
        t("Back to billing", "返回套餐与账单")
      }}</RouterLink>
    </div>
  </section>
</template>
