<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, t, n, money, date, errorText } from "./lib";
const data = ref<any>({}),
  error = ref("");
const emit = defineEmits<{ select: [id: string] }>();
onMounted(async () => {
  try {
    data.value = await api("/workspace");
  } catch (e) {
    error.value = errorText(e);
  }
});
</script>
<template>
  <div v-if="error" class="notice error">{{ error }}</div>
  <section class="panel form-panel">
    <h2>{{ data.workspace?.name }}</h2>
    <p>
      {{
        t(
          "One shared subscription across all your projects. Model costs below include retained data; monthly units follow the current billing period.",
          "所有项目共享一个订阅。模型成本包括保留期内的数据，月度 units 按当前账期统计。",
        )
      }}
    </p>
    <div class="metrics">
      <div>
        <small>{{ t("Current plan", "当前套餐") }}</small>
        <h2>{{ data.workspace?.plan }}</h2>
      </div>
      <div>
        <small>{{ t("Current-period units", "本账期 units") }}</small>
        <h2>{{ n(data.workspace?.used_units) }}</h2>
      </div>
      <div>
        <small>{{ t("Projects", "项目") }}</small>
        <h2>{{ data.projects?.length || 0 }}</h2>
      </div>
      <div>
        <small>{{ t("Period ends", "账期结束") }}</small>
        <p>{{ date(data.workspace?.period_end) }}</p>
      </div>
    </div>
  </section>
  <section class="panel table-wrap">
    <table>
      <thead>
        <tr>
          <th>{{ t("Project", "项目") }}</th>
          <th>{{ t("Environment", "环境") }}</th>
          <th>{{ t("Traces", "调用") }}</th>
          <th>{{ t("Objects", "对象") }}</th>
          <th>{{ t("Model cost (USD)", "模型成本 (USD)") }}</th>
          <th>{{ t("Unknown costs", "未知成本调用") }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in data.projects || []">
          <td>
            <button class="text-button" @click="emit('select', p.id)">
              {{ p.name }}
            </button>
          </td>
          <td>{{ p.environment }}</td>
          <td>{{ n(p.traces) }}</td>
          <td>{{ n(p.objects) }}</td>
          <td>{{ money(p.cost) }}</td>
          <td>{{ n(p.unknown_costs) }}</td>
        </tr>
        <tr v-if="!data.projects?.length">
          <td colspan="6" class="empty-cell">
            <RouterLink to="/app/projects">{{
              t("Create your first project →", "创建第一个项目 →")
            }}</RouterLink>
          </td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
