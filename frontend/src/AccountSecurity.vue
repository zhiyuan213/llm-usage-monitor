<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { api, t, errorText, session } from "./lib";
const router = useRouter(),
  error = ref(""),
  notice = ref(""),
  busy = ref(false);
const email = reactive({
    email: "",
    oldChallengeId: "",
    newChallengeId: "",
    oldCode: "",
    newCode: "",
  }),
  deletion = reactive({ challengeId: "", code: "", confirm: "" });
async function run(fn: () => Promise<void>) {
  busy.value = true;
  error.value = "";
  try {
    await fn();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function emailCode(action: string) {
  await run(async () => {
    const r = await api("/account/email/challenge", {
      email: email.email,
      action,
    });
    if (action === "email.old") email.oldChallengeId = r.challengeId;
    else email.newChallengeId = r.challengeId;
    notice.value = t(
      "Code sent. Wait 60 seconds before requesting the other code. Both expire after 5 minutes.",
      "验证码已发送，请等待 60 秒再发送另一封验证码。两者均为 5 分钟内有效。",
    );
  });
}
</script>
<template>
  <section class="panel form-panel">
    <h2>{{ t("Change email address", "修改邮箱") }}</h2>
    <p>
      {{
        t(
          "Prove control of both addresses. Changing the target address requires new codes. All sessions are revoked after the change.",
          "需要验证旧邮箱与新邮箱。修改目标地址后必须重新获取验证码，变更后撤销所有会话。",
        )
      }}
    </p>
    <div v-if="error" class="notice error">{{ error }}</div>
    <div v-if="notice" class="notice">{{ notice }}</div>
    <form
      @submit.prevent="
        run(async () => {
          await api('/account/email', email);
          session = null;
          router.push('/login');
        })
      "
    >
      <label
        >{{ t("New email", "新邮箱")
        }}<input
          v-model="email.email"
          type="email"
          required
          @input="
            email.oldChallengeId = '';
            email.newChallengeId = '';
          "
      /></label>
      <div class="inline-form">
        <button
          class="btn"
          type="button"
          :disabled="busy || !email.email"
          @click="emailCode('email.old')"
        >
          {{ t("Send to current email", "发送至旧邮箱") }}</button
        ><button
          class="btn"
          type="button"
          :disabled="busy || !email.email"
          @click="emailCode('email.new')"
        >
          {{ t("Send to new email", "发送至新邮箱") }}
        </button>
      </div>
      <div class="form-grid">
        <label
          >{{ t("Current email code", "旧邮箱验证码")
          }}<input v-model="email.oldCode" pattern="[0-9]{6}" required /></label
        ><label
          >{{ t("New email code", "新邮箱验证码")
          }}<input v-model="email.newCode" pattern="[0-9]{6}" required /></label
        ><button
          class="btn primary"
          :disabled="busy || !email.oldChallengeId || !email.newChallengeId"
        >
          {{ t("Change email", "修改邮箱") }}
        </button>
      </div>
    </form>
  </section>
  <section class="panel form-panel danger-zone">
    <h2>{{ t("Close account", "注销账号") }}</h2>
    <p>
      {{
        t(
          "Cancel any active subscription first. API keys and sessions are revoked immediately. Project details are deleted asynchronously; minimal billing and audit records are retained.",
          "请先取消有效订阅。密钥与会话立即撤销，项目明细异步删除，必要计费和审计记录保留。",
        )
      }}
    </p>
    <form
      @submit.prevent="
        run(async () => {
          await api('/account/delete', deletion);
          session = null;
          router.push('/');
        })
      "
    >
      <button
        type="button"
        class="btn"
        :disabled="busy"
        @click="
          run(async () => {
            const r = await api('/verification', {
              action: 'account.delete',
              target: session.id,
            });
            deletion.challengeId = r.challengeId;
          })
        "
      >
        {{ t("Send verification code", "发送验证码") }}
      </button>
      <div class="form-grid">
        <label
          >{{ t("Email code", "邮箱验证码")
          }}<input v-model="deletion.code" pattern="[0-9]{6}" required /></label
        ><label
          >{{ t("Type DELETE to confirm", "输入 DELETE 确认")
          }}<input
            v-model="deletion.confirm"
            pattern="DELETE"
            required /></label
        ><button
          class="btn danger-button"
          :disabled="busy || !deletion.challengeId"
        >
          {{ t("Close my account", "注销账号") }}
        </button>
      </div>
    </form>
  </section>
</template>
