<script setup lang="ts">
import { ref, watch, reactive } from "vue";
import { api, t, date, errorText } from "./lib";
const props = defineProps<{ project: string }>(),
  batches = ref<any[]>([]),
  error = ref("");
const cancel = reactive({
  batch: "",
  challengeId: "",
  code: "",
  confirm: false,
  busy: false,
});
async function refresh() {
  try {
    batches.value = await api("/projects/" + props.project + "/batches");
  } catch (e) {
    error.value = errorText(e);
  }
}
async function replay(id: string) {
  try {
    await api("/projects/" + props.project + "/batches/" + id + "/replay", {});
    await refresh();
  } catch (e) {
    error.value = errorText(e);
  }
}
async function prepare(id: string) {
  error.value = "";
  cancel.batch = id;
  cancel.code = "";
  cancel.confirm = false;
  cancel.challengeId = "";
  cancel.busy = true;
  try {
    const r = await api("/verification", {
      action: "batch.cancel",
      target: props.project + ":" + id,
    });
    cancel.challengeId = r.challengeId;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    cancel.busy = false;
  }
}
async function compensate() {
  cancel.busy = true;
  try {
    await api(
      "/projects/" + props.project + "/batches/" + cancel.batch + "/cancel",
      cancel,
    );
    cancel.batch = "";
    await refresh();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    cancel.busy = false;
  }
}
watch(() => props.project, refresh, { immediate: true });
</script>
<template>
  <section class="panel">
    <div class="panel-heading">
      <h2>{{ t("Recent batches", "最近上报批次") }}</h2>
      <button class="btn" @click="refresh">{{ t("Refresh", "刷新") }}</button>
    </div>
    <div v-if="error && !cancel.batch" class="notice error">{{ error }}</div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{{ t("Received (UTC)", "接收时间 (UTC)") }}</th>
            <th>{{ t("Status", "状态") }}</th>
            <th>
              {{ t("Accepted / duplicate / rejected", "接受 / 重复 / 拒绝") }}
            </th>
            <th>{{ t("Details", "详情") }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="b in batches" :key="b.id">
            <td>
              {{ date(b.created_at) }}<small class="mono">{{ b.id }}</small>
            </td>
            <td>
              <span
                :class="[
                  'badge',
                  b.status === 'quarantined'
                    ? 'danger'
                    : b.status === 'completed'
                      ? 'success'
                      : '',
                ]"
                >{{ b.status }}</span
              >
            </td>
            <td>{{ b.accepted }} / {{ b.duplicates }} / {{ b.rejected }}</td>
            <td>
              <details v-if="b.errors.length">
                <summary>
                  {{ b.errors.length }} {{ t("results", "条结果") }}
                </summary>
                <pre>{{ JSON.stringify(b.errors, null, 2) }}</pre>
              </details>
              <div v-if="b.status === 'quarantined'" class="inline-form">
                <button class="btn" @click="replay(b.id)">
                  {{ t("Replay after repair", "修复后重放") }}</button
                ><button class="btn danger-button" @click="prepare(b.id)">
                  {{ t("Cancel & release", "取消并释放额度") }}
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!batches.length">
            <td colspan="4" class="empty-cell">
              {{ t("No batches received yet.", "尚未收到上报批次。") }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="panel-foot">
      {{
        t(
          "Quarantined batches remain durably stored with units reserved. Repair the underlying cause before replaying; replays never charge a second unit.",
          "隔离批次仍可靠保存并保留额度预留。修复故障后再重放，重放不重复扣费。",
        )
      }}
    </div>
  </section>
  <div v-if="cancel.batch" class="modal-backdrop">
    <section
      class="modal"
      role="dialog"
      aria-modal="true"
      aria-labelledby="cancel-batch-title"
    >
      <h2 id="cancel-batch-title">
        {{ t("Mark batch as permanently failed", "将批次标记为永久失败") }}
      </h2>
      <p>
        {{
          t(
            "Only unprocessed objects without surviving updates are refunded. Closed billing periods require separate review. This batch cannot be replayed after cancellation.",
            "仅释放尚未处理且没有后续有效更新的对象额度。已关闭账期需独立审核。取消后该批次无法再重放。",
          )
        }}
      </p>
      <div v-if="error" class="notice error">{{ error }}</div>
      <form @submit.prevent="compensate">
        <label
          >{{ t("Email verification code", "邮箱验证码")
          }}<input
            v-model="cancel.code"
            pattern="[0-9]{6}"
            maxlength="6"
            required /></label
        ><label class="check-label"
          ><input v-model="cancel.confirm" type="checkbox" required />{{
            t(
              "I confirm permanent failure and eligible unit release.",
              "我确认永久失败并释放符合条件的额度。",
            )
          }}</label
        ><button
          class="btn danger-button"
          :disabled="cancel.busy || !cancel.challengeId"
        >
          {{ t("Confirm cancellation", "确认取消") }}</button
        ><button class="btn" type="button" @click="cancel.batch = ''">
          {{ t("Keep batch", "保留批次") }}
        </button>
      </form>
    </section>
  </div>
</template>
