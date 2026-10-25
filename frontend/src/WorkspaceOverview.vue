<script setup lang="ts">
import IngestionNotice from "./IngestionNotice.vue";
import { Plus, Gem, Database, FolderKanban, CalendarDays } from "lucide-vue-next";
import { t, n, money, date } from "./lib";
defineProps<{ data: any }>();
const emit = defineEmits<{ select: [id: string, page?: string]; create: [] }>();
const actions = [
  ["traces", "Traces", "调用记录"],
  ["analytics", "Data analytics", "数据统计"],
  ["alerts", "Alert configuration", "告警配置"],
  ["settings", "Project settings", "项目设置"],
] as const;
function more(event:Event,id:string){const select=event.target as HTMLSelectElement;if(select.value)emit('select',id,select.value);select.value='';}
</script>
<template>
  <section class="panel form-panel workspace-summary">
    <div class="workspace-summary-heading">
      <span class="workspace-avatar" aria-hidden="true">{{ (data.workspace?.name || 'M').slice(0, 1).toUpperCase() }}</span>
      <div class="workspace-heading-copy">
        <div class="workspace-title-row">
          <h2>{{ data.workspace?.name }}</h2>
          <span class="workspace-plan-badge">{{ data.workspace?.plan }}</span>
        </div>
        <p>{{ t("Workspace overview", "工作空间概览") }}</p>
      </div>
    </div>
    <p>
      {{
        t(
          "One shared subscription across all your projects. Model costs below include retained data; monthly units follow the current billing period.",
          "所有项目共享一个订阅。模型成本包括保留期内的数据，月度 units 按当前账期统计。",
        )
      }}
    </p>
    <IngestionNotice :state="data.workspace?.ingestion" />
    <div class="workspace-summary-metrics">
      <div class="workspace-stat workspace-stat-plan">
        <span class="workspace-stat-icon" aria-hidden="true"><Gem :size="24" /></span>
        <div class="workspace-stat-copy">
        <small>{{ t("New data ingestion", "新增接入状态") }}</small>
        <h2>{{!data.workspace?.ingestion?t("Checking","待确认"):data.workspace.ingestion.can_accept_new?t("Available","可接入"):t("Paused","已暂停")}}</h2>
        </div>
      </div>
      <div class="workspace-stat workspace-stat-usage">
        <span class="workspace-stat-icon" aria-hidden="true"><Database :size="24" /></span>
        <div class="workspace-stat-copy">
        <small>{{ t("Current-period units", "本账期 units") }}</small>
        <h2>{{ n(data.workspace?.used_units) }}</h2><small>/ {{n((data.workspace?.plan==='Free'?50000:100000)+Number(data.workspace?.addon_units||0))}} {{t('included + purchased','套餐与加购额度')}}</small>
        </div>
      </div>
      <div class="workspace-stat workspace-stat-projects">
        <span class="workspace-stat-icon" aria-hidden="true"><FolderKanban :size="24" /></span>
        <div class="workspace-stat-copy">
        <small>{{ t("Projects with retained data", "有保留数据的项目") }}</small>
        <h2>{{ data.projects?.filter((p:any)=>Number(p.objects)>0).length || 0 }}</h2>
        </div>
      </div>
      <div class="workspace-stat workspace-stat-period">
        <span class="workspace-stat-icon" aria-hidden="true"><CalendarDays :size="24" /></span>
        <div class="workspace-stat-copy">
        <small>{{ t("Period ends", "账期结束") }}</small>
        <h2 class="workspace-period-date">{{ date(data.workspace?.period_end).split(' ')[0] }}</h2>
        <span class="workspace-period-time" v-if="data.workspace?.period_end">{{ date(data.workspace.period_end).split(' ').slice(1).join(' ') }}</span>
        </div>
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
          <th>{{ t("Requests", "应用请求") }}</th>
          <th>{{ t("Objects", "对象") }}</th>
          <th>{{ t("Known model cost (USD)", "已知模型费用 (USD)") }}</th>
          <th>{{ t("Unknown costs", "未知成本调用") }}</th>
          <th>{{ t("Latest activity (UTC)", "最近活动 (UTC)") }}</th>
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
          <td><span class="badge">{{ p.environment }}</span></td>
          <td>{{ n(p.traces) }}</td>
          <td>{{ n(p.objects) }}</td>
          <td>{{ money(p.cost) }}<small v-if="Number(p.unknown_costs)>0" class="muted">{{t('Partial subtotal','非完整总额')}}</small></td>
          <td>{{ n(p.unknown_costs) }}</td>
          <td :title="t('Latest retained object start time','保留期内对象的最近开始时间')">{{ date(p.last_activity_at) }}</td>
          <td>
            <div class="project-row-actions"><button class="text-button" @click="emit('select',p.id,'overview')">{{t('Open project','进入项目')}}</button><button class="text-button" @click="emit('select',p.id,'keys')">{{t('Connect','接入')}}</button><select :aria-label="p.name+' · '+t('More actions','更多操作')" @change="more($event,p.id)"><option value="">{{t('More','更多')}}</option><option v-for="action in actions" :key="action[0]" :value="action[0]">{{t(action[1],action[2])}}</option></select></div>
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
