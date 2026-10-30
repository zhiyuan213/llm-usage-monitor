<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { api, t, errorText } from "./lib";
const props = defineProps<{ project: any }>();
const emit = defineEmits<{ saved: [] }>();
const form = reactive({ name: "", environment: "" });
const error = ref(""), busy = ref(false);
const thresholdMs = ref(10000), thresholdBusy = ref(false), thresholdError = ref("");
watch(() => props.project?.id, () => {
  form.name = props.project?.name || "";
  form.environment = props.project?.environment || "";
  error.value=""; thresholdError.value="";
}, { immediate: true });
watch(() => [props.project?.id, props.project?.slow_call_threshold_ms], () => {
  thresholdMs.value=Number(props.project?.slow_call_threshold_ms ?? 10000);
}, { immediate: true });
async function saveThreshold() {
  if (!Number.isInteger(thresholdMs.value) || thresholdMs.value<1 || thresholdMs.value>600000) {
    thresholdError.value=t("Enter 1–600,000 milliseconds", "请输入1至600,000之间的整数毫秒数"); return;
  }
  thresholdBusy.value=true; thresholdError.value="";
  try { await api("/projects/"+props.project.id+"/slow-threshold",{milliseconds:thresholdMs.value}); emit("saved"); }
  catch(e) { thresholdError.value=errorText(e); } finally { thresholdBusy.value=false; }
}
async function save() {
  busy.value=true; error.value="";
  try { await api("/projects/"+props.project.id+"/settings",form); emit("saved"); }
  catch(e) { error.value=errorText(e); } finally { busy.value=false; }
}
</script>
<template>
  <div class="project-settings-top-grid">
    <section class="panel form-panel">
      <h2>{{t('Project configuration','项目配置')}}</h2>
      <div v-if="error" class="notice error">{{error}}</div>
      <form class="form-grid" @submit.prevent="save">
        <label>{{t('Project name','项目名称')}}<input v-model="form.name" maxlength="128" required :disabled="busy" /></label>
        <label>{{t('Environment','环境')}}<input v-model="form.environment" maxlength="128" required :disabled="busy" /></label>
        <button class="btn primary" :disabled="busy">{{t('Save changes','保存变更')}}</button>
      </form>
    </section>
    <section class="panel form-panel project-threshold-panel">
      <h2>{{t('Threshold settings','阈值设置')}}</h2>
      <form @submit.prevent="saveThreshold">
        <label>{{t('Slow-call threshold (ms)','慢调用阈值（毫秒）')}}<input v-model.number="thresholdMs" type="number" min="1" max="600000" step="1" required :disabled="thresholdBusy" /></label>
        <p class="muted">{{t('Default: 10,000 ms. An Observation at or above this duration is a slow call; the total Trace duration is not used.','默认10000毫秒。单条 Observation 耗时达到阈值即为慢调用，不按 Trace 总耗时判断。')}}</p>
        <p v-if="thresholdError" class="notice error">{{thresholdError}}</p>
        <button class="btn primary" :disabled="thresholdBusy">{{t('Save threshold','保存阈值')}}</button>
      </form>
    </section>
  </div>
</template>
