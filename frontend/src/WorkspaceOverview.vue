<script setup lang="ts">
import { Plus } from "lucide-vue-next";
import { t, n, money, date } from "./lib";
defineProps<{ data: any }>();
const emit = defineEmits<{ select: [id: string, page?: string]; create: [] }>();
const actions = [
  ["overview", "Overview", "概览"],
  ["traces", "Traces", "调用记录"],
  ["analytics", "Data analytics", "数据统计"],
  ["keys", "API keys", "密钥管理"],
  ["alerts", "Alert configuration", "告警配置"],
  ["settings", "Project settings", "项目配置"],
] as const;
</script>
<template>
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
  <div class="section-heading">
    <h2>
      {{ t("Your projects", "你的项目") }}
      <span class="count">{{ data.projects?.length || 0 }}</span>
    </h2>
    <button class="btn primary" @click="emit('create')">
      <Plus :size="16" />{{ t("New project", "新建项目") }}
    </button>
  </div>
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
          <th>{{ t("Created (UTC)", "创建时间 (UTC)") }}</th>
          <th>{{ t("Actions", "操作") }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in data.projects || []" :key="p.id">
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
          <td>{{ date(p.created_at) }}</td>
          <td>
            <div class="project-row-actions">
              <button
                v-for="action in actions"
                :key="action[0]"
                class="btn"
                :aria-label="p.name + ' · ' + t(action[1], action[2])"
                @click="emit('select', p.id, action[0])"
              >
                {{ t(action[1], action[2]) }}
              </button>
            </div>
          </td>
        </tr>
        <tr v-if="!data.projects?.length">
          <td colspan="8" class="empty-cell">
            <button class="btn" @click="emit('create')">
              {{ t("Create your first project →", "创建第一个项目 →") }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
