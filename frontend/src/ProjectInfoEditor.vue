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
    <h2>{{ t("Project configuration", "项目配置") }}</h2>
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
  </section>
</template>
