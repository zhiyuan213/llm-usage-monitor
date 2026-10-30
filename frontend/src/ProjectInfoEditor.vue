<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { api, t, errorText } from "./lib";
const props = defineProps<{ project: any }>(),
  emit = defineEmits<{ saved: [] }>(),
  form = reactive({ name: "", environment: "" }),
  error = ref(""),
  busy = ref(false);
watch(
  () => props.project?.id,
  () => {
    form.name = props.project?.name || "";
    form.environment = props.project?.environment || "";
  },
  { immediate: true },
);
const thresholdOpen=ref(false), thresholdSeconds=ref(10), thresholdBusy=ref(false), thresholdError=ref("");
watch(() => props.project?.id, () => { thresholdOpen.value=false; thresholdError.value=""; });
function openThreshold() { thresholdSeconds.value=Number(props.project?.slow_call_threshold_ms || 10000)/1000; thresholdError.value=""; thresholdOpen.value=true; }
async function saveThreshold() {
 if (!Number.isInteger(thresholdSeconds.value) || thresholdSeconds.value<1 || thresholdSeconds.value>600) { thresholdError.value=t("Enter 1–600 seconds", "请输入1至600之间的整数秒数"); return; }
 thresholdBusy.value=true; thresholdError.value="";
 try { await api("/projects/"+props.project.id+"/slow-threshold",{seconds:thresholdSeconds.value}); thresholdOpen.value=false; emit("saved"); }
 catch(e) { thresholdError.value=errorText(e); } finally { thresholdBusy.value=false; }
}
async function save() {
  busy.value = true;
  try {
    await api("/projects/" + props.project.id + "/settings", form);
    emit("saved");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="panel form-panel">
    <div class="project-config-heading"><h2>{{ t("Project configuration", "项目配置") }}</h2><button class="btn" type="button" @click="openThreshold">{{t("Slow-call threshold", "慢调用阈值")}}</button></div>
    <div v-if="error" class="notice error">{{ error }}</div>
    <form class="form-grid" @submit.prevent="save">
      <label
        >{{ t("Project name", "项目名称")
        }}<input v-model="form.name" maxlength="128" required /></label
      ><label
        >{{ t("Environment", "环境")
        }}<input v-model="form.environment" maxlength="128" required /></label
      ><button class="btn primary" :disabled="busy">
        {{ t("Save changes", "保存变更") }}
      </button>
    </form>
    <div v-if="thresholdOpen" class="modal-backdrop" @keydown.esc="!thresholdBusy && (thresholdOpen=false)">
      <section class="modal" role="dialog" aria-modal="true" aria-labelledby="threshold-title">
        <h2 id="threshold-title">{{t('Slow-call threshold','慢调用阈值')}}</h2>
        <p>{{t('Applies to each Observation, not the total Trace duration. Default: 10 seconds.','按单条 Observation 耗时判断，不按 Trace 总耗时。默认10秒。')}}</p>
        <form @submit.prevent="saveThreshold">
          <label>{{t('Threshold (seconds)','阈值（秒）')}}<input v-model.number="thresholdSeconds" type="number" min="1" max="600" step="1" required :disabled="thresholdBusy" /></label>
          <p v-if="thresholdError" class="notice error">{{thresholdError}}</p>
          <div class="project-config-heading"><button class="btn" type="button" :disabled="thresholdBusy" @click="thresholdOpen=false">{{t('Cancel','取消')}}</button><button class="btn primary" :disabled="thresholdBusy">{{t('Save','保存')}}</button></div>
        </form>
      </section>
    </div>
  </section>
</template>
