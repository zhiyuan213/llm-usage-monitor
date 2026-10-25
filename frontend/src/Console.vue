<script setup lang="ts">
import DateRangeFilter from "./DateRangeFilter.vue";
import SupportMessages from "./SupportMessages.vue";
import AdminProjects from "./AdminProjects.vue";
import AnalyticsReport from "./AnalyticsReport.vue";
import BusinessInsights from './BusinessInsights.vue';
import BudgetReminders from './BudgetReminders.vue';
import ReliabilityInsights from './ReliabilityInsights.vue';
import UsageCharges from "./UsageCharges.vue";
import BillingSubscription from "./BillingSubscription.vue";
import BillingHistory from "./BillingHistory.vue";
import PlanFeatures from "./PlanFeatures.vue";
import QueryControls from "./QueryControls.vue";
import WorkspaceOverview from "./WorkspaceOverview.vue";
import ProjectInfoEditor from "./ProjectInfoEditor.vue";
import BatchHistory from "./BatchHistory.vue";
import AccountSecurity from "./AccountSecurity.vue";
import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  MessageSquare,
  Inbox,
  Activity,
  LayoutDashboard,
  FolderKanban,
  GitBranch,
  KeyRound,
  Bell,
  CreditCard,
  Settings,
  ScrollText,
  ArrowUpRight,
  Plus,
  RefreshCw,
  Search,
  ChevronRight,
  LogOut,
  BookOpen,
  Copy,
  Check,
  X,
  ShieldCheck,
  Clock,
  ArrowLeft,
  BarChart3,
} from "lucide-vue-next";
import {
  api,
  t,
  n,
  money,
  date,
  session,
  language,
  ApiError,
  errorText,
} from "./lib";
import { contextLoader } from "./context-loader";
import { verificationFlow } from "./verification";
const route = useRoute(),
  router = useRouter(),
  page = computed(() => String(route.params.page || "workspace"));
const nav = [
  ["workspace", "Project management", "项目管理", LayoutDashboard],
  ["overview", "Overview", "概览", Activity],
  ["traces", "Traces", "调用记录", GitBranch],
  ["analytics", "Data analytics", "数据统计", BarChart3],
  ["cost-insights", "Cost diagnosis", "费用诊断", BarChart3],
  ["releases", "Release comparison", "发布对比", GitBranch],
  ["weekly", "Weekly reports", "自动周报", ScrollText],
  ["budgets", "Budget reminders", "预算提醒", Bell],
  ["reliability", "Failures and slow calls", "失败与慢调用", Activity],
  ["keys", "API key management", "密钥管理", KeyRound],
  ["alerts", "Alert configuration", "告警配置", Bell],
  ["billing", "Billing", "套餐与账单", CreditCard],
  ["settings", "Project settings", "项目设置", Settings],
  ["account", "Account security", "账号安全", ShieldCheck],
  ["audit", "Audit logs", "审计日志", ScrollText],
  ["messages", "Messages & feedback", "消息反馈", MessageSquare],
  ["admin-messages", "User messages", "用户消息", Inbox],
  ["admin-projects", "Project statistics", "项目统计", BarChart3],
] as const;
const navGroups = computed(() => [
  {
    en: "Projects",
    zh: "项目",
    items: nav.filter(
      (item) => !["account", "billing", "audit", "messages", "admin-messages", "admin-projects"].includes(item[0]),
    ),
  },
  {
    en: "Account",
    zh: "账号",
    items: ["account", "billing", "audit", "messages"].map((id) =>
      nav.find((item) => item[0] === id)!,
    ),
  },
  ...(session.value?.isAdmin ? [{en: "Administrator", zh: "管理员", items: nav.filter((item) => ["admin-messages", "admin-projects"].includes(item[0]))}] : []),
]);
const title = computed(() => {
  const item = nav.find((v) => v[0] === page.value);
  return item ? t(item[1], item[2]) : t("Trace detail", "调用详情");
});
const projects = ref<any[]>([]),
  selected = ref(localStorage.getItem("metertrail.project") || ""),
  loading = ref(true),
  contextReady = ref(false),
  signingOut = ref(false),
  error = ref(""),
  notice = ref(""),
  data = ref<any>({}),
  overview = ref<any>({}),
  recent = ref<any[]>([]);
const reportWindow=ref({from:new Date(Date.now()-7*86400000).toISOString(),to:new Date().toISOString()});
const reportWindowQuery=computed(()=>new URLSearchParams(reportWindow.value).toString());
const traceSort=ref('started_at'),traceDirection=ref('desc');
function applyDates(value:{from:string;to:string}){reportWindow.value=value;tracePage.value=0;activityPage.value=0;refresh(false);}
function sortTraces(value:string){traceDirection.value=traceSort.value===value&&traceDirection.value==='desc'?'asc':'desc';traceSort.value=value;tracePage.value=0;refresh(false);}
const pageDescription=computed(()=>({overview:t('Request performance, known model cost and usage for the selected period.','查看所选时间内的请求表现、已知模型费用和用量。'),traces:t('Find requests by name or status, then compare latency and model usage.','检索请求，比较耗时、模型用量与已知费用。'),trace:t('Inspect request steps, model usage and pricing evidence.','查看请求步骤、模型用量与计费依据。'),analytics:t('Compare usage by customer, user or feature.','按客户、用户或功能比较用量。'),'cost-insights':t('Find cost drivers and compare equal-length periods.','定位费用来源，对比等长时间段的变化。'),releases:t('Mark releases and compare before and after.','记录发布时间，对比修改前后的表现。'),weekly:t('Manage weekly email summaries and delivery history.','管理每周邮件摘要与发送记录。'),billing:t('Manage your plan, quota and payment records.','管理套餐、额度和付款记录。'),keys:t('Create and revoke project API keys.','创建与撤销项目接入密钥。'),settings:t('Manage project details and model price versions.','管理项目资料与模型价格版本。'),account:t('Manage sign-in methods and account security.','管理登录方式与账号安全。'),messages:t('Send feedback and follow replies.','提交问题与建议，查看回复。'),workspace:t('Manage projects and shared workspace usage.','管理项目与工作空间共享用量。'),budgets:t('Set model spending reminders.','设置模型费用预算提醒。'),reliability:t('Investigate failed and slow requests.','排查失败请求与慢调用。'),alerts:t('Configure notifications for project activity.','配置项目用量与异常通知。'),audit:t('Review account and workspace actions.','查看账号与工作空间操作记录。') } as Record<string,string>)[page.value]||t('Review and manage this section.','查看与管理本栏目内容。'));
function fieldLabel(key:string){return ({name:t('Name','名称'),status:t('Status','状态'),model:t('Model','模型'),provider:t('Provider','供应商'),usage:t('Token usage','Token 用量'),durationMs:t('Duration (ms)','耗时（毫秒）'),observationType:t('Step type','步骤类型'),userId:t('User','用户'),customerId:t('Customer','客户'),feature:t('Feature','功能'),metadata:t('Metadata','附加信息'),input:t('Input','输入'),output:t('Output','输出'),tags:t('Tags','标签'),error:t('Error','错误信息'),value:t('Value','评分值'),valueType:t('Value type','评分类型'),targetId:t('Target ID','关联对象 ID'),targetType:t('Target type','关联对象类型')} as Record<string,string>)[key]||key;}
function objectLabel(value:string){return ({trace:t('Request','请求'),observation:t('Step','步骤'),score:t('Score','评分'),generation:t('Model generation','模型生成'),tool:t('Tool','工具'),retrieval:t('Retrieval','检索')} as Record<string,string>)[value]||value;}
function costReason(o:any){if(o.type!=='observation'||o.data.observationType!=='generation')return t('Not a model generation call','非模型生成调用');const u=o.data.usage;if(!u||!['inputTokens','outputTokens','cachedInputTokens'].every(k=>typeof u[k]==='number'))return t('Missing input, output or cached token usage','缺少输入、输出或缓存 Token 用量');if(!o.data.provider||!o.data.model)return t('Missing model or provider','缺少模型或供应商');return t('No matching price at processing time','处理时未命中适用的价格版本');}
const traceSummary=computed(()=>{const calls=traceObjects.value.filter((o:any)=>o.type==='observation'&&o.data.observationType==='generation');const priced=calls.filter((o:any)=>o.cost!=null);const usage=calls.filter((o:any)=>o.data.usage?.inputTokens!=null&&o.data.usage?.outputTokens!=null);return {calls:calls.length,priced:priced.length,cost:priced.length?priced.reduce((n:number,o:any)=>n+Number(o.cost),0):null,tokens:usage.length?usage.reduce((n:number,o:any)=>n+Number(o.data.usage.inputTokens)+Number(o.data.usage.outputTokens),0):null,duration:traceObjects.value.find((o:any)=>o.type==='trace')?.data.durationMs};});
const accountReady = computed(() => contextReady.value && !loading.value && !!session.value?.csrf);
const activityPage = ref(0),
  activitySize = ref(5),
  activityTotal = ref(0),
  reportPage = ref(0),
  reportSize = ref(5);
const reportExportUrl = computed(
  () =>
    "/api/reports/" +
    page.value +
    "/export?" +
    new URLSearchParams({
      project: selected.value,
      dimension: dimension.value,
      search: search.value,
      status: status.value,
      ...reportWindow.value, sort:traceSort.value,direction:traceDirection.value,
    }),
);
async function reportTurn(next: number) {
  reportPage.value = next;
  await refresh(false);
}
const project = computed(() =>
  projects.value.find((p) => p.id === selected.value),
);
const search = ref(""),
  status = ref(""),
  tracePage = ref(0),
  dimension = ref("customerId"),
  showCreate = ref(false),
  createdKey = ref(""),
  keyName = ref("Production key"),
  copied = ref(false);
const changeTargetPlan = ref("");
const newProject = reactive({ name: "", environment: "production" }),
  alert = reactive({ name: "", metric: "error_count", threshold: "10" }),
  price = reactive({
    provider: "",
    model: "",
    effectiveAt: new Date().toISOString().slice(0, 16),
    inputRate: "",
    outputRate: "",
    cachedRate: "",
  }),
  billing = reactive({ enabled: false, cap: "0", consent: false }),
  deleteConfirm = ref("");
const { verification, openVerification, sendCode } = verificationFlow(
  (action, target) => api("/verification", { action, target }),
  errorText,
);
let perform: (proof: any) => Promise<void> = async () => {};
async function verify(
  action: string,
  target: string,
  fn: (proof: any) => Promise<void>,
) {
  if (!accountReady.value) return;
  perform = fn;
  openVerification(action, target);
}
async function confirm() {
  if (verification.busy || !verification.challengeId) return;
  verification.busy = true;
  verification.error = "";
  try {
    await perform({
      challengeId: verification.challengeId,
      code: verification.code,
    });
    verification.open = false;
    notice.value = t("Changes saved.", "变更已保存。");
    await refresh();
  } catch (e) {
    verification.error = errorText(e);
  } finally {
    verification.busy = false;
  }
}
async function run(fn: () => Promise<void>) {
  if (!accountReady.value) return;
  error.value = "";
  try {
    await fn();
  } catch (e) {
    error.value = errorText(e);
  }
}
const navigate = (url: string) => window.location.assign(url);
const path = (suffix: string) => "/projects/" + selected.value + suffix;
const loadContext = contextLoader(() =>
  Promise.all([api("/projects"), api("/me")]),
);
let refreshId = 0;
async function refresh(force = true) {
  const id = ++refreshId;
  const stale = Symbol("superseded refresh");
  const current = () => {
    if (id !== refreshId) throw stale;
  };
  const request = async (url: string) => {
    const result = await api(url);
    current();
    return result;
  };
  loading.value = true;
  contextReady.value = false;
  error.value = "";
  try {
    const [projectList, user] = await loadContext(
      force || page.value === "account",
    );
    current();
    projects.value = projectList;
    session.value = user;
    contextReady.value = true;
    if (typeof route.query.project === 'string' && projectList.some((p:any)=>p.id===route.query.project)) selected.value=route.query.project;
    if (!projects.value.some((p) => p.id === selected.value))
      selected.value = projects.value[0]?.id || "";
    localStorage.setItem("metertrail.project", selected.value);
    if (page.value === "billing") {
      data.value = await request("/billing");
      if (session.value?.workspace) Object.assign(session.value.workspace, data.value.workspace);
      billing.enabled = data.value.workspace.auto_overage;
      billing.cap = String(data.value.workspace.overage_cap);
    } else if (page.value === "workspace")
      data.value = await request("/workspace");
    else if (page.value === "audit")
      data.value = await request(
        "/reports/audit?page=" + reportPage.value + "&size=" + reportSize.value,
      );
    else if (["account", "messages", "admin-messages", "admin-projects"].includes(page.value)) {
      /* Account is included in context. */
    } else if (selected.value) {
      if (page.value === "overview") {
        const [o, r] = await Promise.all([
          request(path("/overview")+"?"+new URLSearchParams(reportWindow.value)),
          request(
            path("/activity") +
              "?page=" +
              activityPage.value +
              "&size=" +
              activitySize.value + "&" + new URLSearchParams(reportWindow.value),
          ),
        ]);
        current();
        overview.value = o;
        recent.value = r.items;
        activityTotal.value = r.total;
      } else if (page.value === "traces")
        data.value = await request(
          "/reports/traces" +
            "?" +
            new URLSearchParams({
              search: search.value,
              project: selected.value,
              status: status.value,
              page: String(tracePage.value),
              size: String(reportSize.value),
              ...reportWindow.value,sort:traceSort.value,direction:traceDirection.value,
            }),
        );
      else if (page.value === "trace")
        data.value = await request(path("/traces/" + route.params.detail));
      else if (page.value === "analytics") data.value = {};
      else if (page.value === "keys") data.value = await request(path("/keys"));
      else if (page.value === "alerts")
        data.value = await request(path("/alerts"));
      else if (page.value === "settings")
        data.value = await request(path("/prices"));
    }
  } catch (e) {
    if (id !== refreshId) return;
    error.value = errorText(e);
    if (e instanceof ApiError && e.status === 401) {
      session.value = null;
      router.replace("/login");
    }
  } finally {
    if (id === refreshId) loading.value = false;
  }
}
onBeforeUnmount(() => {
  refreshId++;
});
onMounted(() => refresh(false));
watch(
  () => route.fullPath,
  () => {
    reportPage.value = 0;
    tracePage.value = 0;
    data.value = {};
    notice.value = "";
    createdKey.value = "";
    refresh(false);
  },
);
async function changeProject() {
  reportPage.value = 0;
  activityPage.value = 0;
  tracePage.value = 0;
  if (typeof route.query.project === 'string' && route.query.project !== selected.value) {
    await router.replace({path:route.path,query:{...route.query,project:selected.value}});
    return;
  }
  await refresh(false);
}
function openProject(id: string, target = "overview") {
  activityPage.value = 0;
  selected.value = id;
  localStorage.setItem("metertrail.project", id);
  router.push("/app/" + target);
}
async function activityTurn(page: number) {
  activityPage.value = page;
  await refresh(false);
}
async function createProject() {
  await run(async () => {
    const p = await api("/projects", newProject);
    selected.value = p.id;
    showCreate.value = false;
    newProject.name = "";
    await refresh();
  });
}
async function removeEntry(kind: "keys" | "alerts", id: string) {
  const message =
    kind === "keys"
      ? t(
          "Delete this revoked key? Historical data and audit records will remain.",
          "删除这条已撤销密钥？历史数据和审计记录会保留。",
        )
      : t(
          "Delete this alert rule? It will stop evaluating. Alert history and audit records will remain.",
          "删除这条告警规则？删除后停止检测，历史告警和审计记录会保留。",
        );
  if (!window.confirm(message)) return;
  const url = path("/" + kind + "/" + id + "/delete");
  await run(async () => {
    await api(url, {});
    await refresh();
  });
}
async function createKey() {
  const p = selected.value,
    name = keyName.value;
  await verify("key.create", p + ":" + name, async (proof) => {
    const result = await api("/projects/" + p + "/keys", { ...proof, name });
    createdKey.value = result.key;
  });
}
async function revoke(key: string) {
  const p = selected.value;
  await verify("key.revoke", p + ":" + key, async (proof) => {
    await api("/projects/" + p + "/keys/" + key + "/revoke", proof);
  });
}
async function saveOverage() {
  const cap = Number(billing.cap).toString(),
    enabled = billing.enabled,
    consent = billing.consent;
  await verify("billing.overage", enabled + ":" + cap, async (proof) => {
    await api("/billing/overage", { ...proof, cap, enabled, consent });
  });
}
async function deleteProject() {
  const p = selected.value,
    confirm = deleteConfirm.value;
  await verify("project.delete", p, async (proof) => {
    await api("/projects/" + p + "/delete", { ...proof, confirm });
    selected.value = "";
    router.push("/app/workspace");
  });
}
async function copyKey() {
  await navigator.clipboard.writeText(createdKey.value);
  copied.value = true;
}
async function logout() {
  if (!accountReady.value || signingOut.value) return;
  signingOut.value = true;
  try {
    await api("/logout", {});
    refreshId++;
    contextReady.value = false;
    session.value = null;
    await router.push("/login");
  } finally {
    signingOut.value = false;
  }
}
const daily = computed(() => [...(overview.value.daily || [])].reverse()),
  maxCalls = computed(() =>
    Math.max(1, ...daily.value.map((d: any) => d.calls)),
  );
const traceObjects = computed(() =>
  [...(data.value.objects || [])].sort(
    (a: any, b: any) =>
      new Date(a.started_at).getTime() - new Date(b.started_at).getTime() ||
      (a.type === "trace" ? -1 : b.type === "trace" ? 1 : 0),
  ),
);
const traceStart = computed(() =>
  Math.min(
    ...traceObjects.value
      .filter((o: any) => o.type !== "score")
      .map((o: any) => new Date(o.started_at).getTime()),
  ),
);
const traceDuration = computed(() =>
  Math.max(
    1,
    ...traceObjects.value
      .filter((o: any) => o.type !== "score")
      .map(
        (o: any) =>
          new Date(o.started_at).getTime() +
          (o.data.durationMs || 0) -
          traceStart.value,
      ),
  ),
);
const usagePercent = computed(() =>
  Math.min(
    100,
    (100 * Number(session.value?.workspace.used_units || 0)) /
      (session.value?.workspace.plan === "Free" ? 50000 : 100000),
  ),
);
</script>
<template>
  <div class="console-shell ui-v2" :data-page="page">
    <aside class="sidebar">
      <RouterLink to="/" class="brand"
        ><span class="brand-icon"><Activity :size="20" /></span
        >MeterTrail</RouterLink
      >
      <div class="workspace-switch">
        <span class="avatar">M</span>
        <div>
          {{ session?.workspace.name || "My workspace"
          }}<small
            >{{ session?.workspace.plan || "Free" }}
            {{ t("plan", "套餐") }}</small
          >
        </div>
      </div>
      <nav>
        <template v-for="group in navGroups" :key="group.en">
          <div class="nav-group-label">{{ t(group.en, group.zh) }}</div>
          <RouterLink
            v-for="item in group.items"
            :key="item[0]"
            :to="'/app/' + item[0]"
            :aria-label="t(item[1], item[2])"
            :title="t(item[1], item[2])"
            :class="{
              active:
                page === item[0] || (page === 'trace' && item[0] === 'traces'),
            }"
            ><component :is="item[3]" :size="18" /><span>{{
              t(item[1], item[2])
            }}</span></RouterLink
          >
        </template>
      </nav>
      <div class="sidebar-bottom">
        <div class="usage-label">
          <span>{{ t("Monthly units", "本月 units") }}</span
          ><b>{{ n(session?.workspace.used_units || 0) }}</b>
        </div>
        <div class="progress">
          <i :style="{ width: usagePercent + '%' }"></i>
        </div>
        <small>{{ t("Stop at quota enabled", "默认额度保护已启用") }}</small
        ><RouterLink to="/docs"
          ><BookOpen :size="16" />{{ t("Documentation", "开发文档")
          }}<ArrowUpRight :size="14"
        /></RouterLink>
        <div class="account-summary">
          <span class="avatar">{{ session?.email?.[0]?.toUpperCase() }}</span
          ><span :title="session?.email">{{ session?.email }}</span
          ><button
            class="icon-button"
            :title="t('Sign out', '退出登录')"
            :disabled="!accountReady || signingOut"
            @click="run(logout)"
          >
            <LogOut :size="16" />
          </button>
        </div>
      </div>
    </aside>
    <div class="console-main">
      <header class="console-header">
        <div class="breadcrumb">
          {{ t("Project management", "项目管理")
          }}<ChevronRight :size="13" /><span>{{ title }}</span>
        </div>
        <div class="header-status">
          <span class="live-dot"></span
          >{{ t("All times in UTC", "时间均为 UTC") }}
        </div>
      </header>
      <main
        class="console-content"
        :class="{
          'overview-content': page === 'overview' && !!selected,
          'billing-content': page === 'billing',
        }"
        :aria-busy="loading"
      >
        <div class="page-heading">
          <div>
            <div class="eyebrow">METERTRAIL CONSOLE</div>
            <h1>{{ title }}</h1>
            <p>{{ pageDescription }}</p>
          </div>
          <div class="toolbar">
            <select
              v-if="
                !['billing', 'audit', 'account', 'workspace', 'messages', 'admin-messages', 'admin-projects'].includes(page) &&
                projects.length
              "
              v-model="selected"
              @change="changeProject"
              aria-label="Project"
            >
              <option v-for="p in projects" :value="p.id">
                {{ p.name }}
              </option></select
            ><button
              class="btn"
              :disabled="loading"
              :aria-label="
                loading
                  ? t('Loading data', '正在加载数据')
                  : t('Refresh', '刷新')
              "
              @click="refresh()"
            >
              <RefreshCw
                :size="15"
                :class="{ spin: loading }"
                aria-hidden="true"
              />{{ t("Refresh", "刷新") }}
            </button>
          </div>
        </div>
        <div v-if="route.query.oauth === 'cancelled'" class="notice" role="status">
          {{ t("GitHub authorization was cancelled. You can try again or sign in with email.", "已取消 GitHub 授权，可以重试或使用邮箱登录。") }}
        </div>
        <div v-if="route.query.oauth === 'failed'" class="notice error" role="alert">
          {{ t("GitHub authorization failed. Please try again.", "GitHub 授权失败，请重试。") }}
        </div>
        <div v-if="error" class="notice error" role="alert">{{ error }}</div>
        <div v-if="notice" class="notice success">{{ notice }}</div>
        <div
          v-if="
            !loading &&
            !selected &&
            !['billing', 'audit', 'account', 'workspace', 'messages', 'admin-messages', 'admin-projects'].includes(page)
          "
          class="panel empty"
        >
          <FolderKanban :size="42" />
          <h2>{{ t("Your first project starts here", "从第一个项目开始") }}</h2>
          <p>
            {{
              t(
                "Create a project, generate an API key, and send your first trace.",
                "创建项目、生成密钥，然后发送第一条 Trace。",
              )
            }}
          </p>
          <button class="btn primary" @click="showCreate = true">
            <Plus :size="16" />{{ t("Create project", "创建项目") }}
          </button>
        </div>
        <template v-if="page === 'overview' && selected"
          ><DateRangeFilter :busy="loading" @change="applyDates" /><p class="muted">{{date(reportWindow.from)}} → {{date(reportWindow.to)}} · {{t("End exclusive; retained data only.","不含结束时刻；仅统计保留期内数据。")}}</p><div class="project-caption">
            <span class="badge success">{{ project?.environment }}</span
            ><strong>{{ project?.name }}</strong
            ><span class="muted">{{
              t("Retained data · UTC", "保留期内数据 · UTC")
            }}</span>
          </div>
          <div class="metrics">
            <article
              class="panel metric"
              v-for="(v, i) in [
                [
                  t('Model calls', '模型调用'),
                  n(overview.summary?.calls),
                  t('Generation observations', '模型生成步骤'),
                ],
                [
                  t('Total tokens', '总 Token'),
                  n(overview.summary?.tokens),
                  t('Known input + output', '已知输入 + 输出'),
                ],
                [
                  t('Known estimated model cost', '已知模型估算费用'),
                  money(overview.summary?.cost),
                  `${n(Number(overview.summary?.calls||0)-Number(overview.summary?.unknown_costs||0))}/${n(overview.summary?.calls)} ` + t('model calls priced', '条模型调用已定价'),
                ],
                [
                  t('P95 latency', 'P95 延迟'),
                  n(overview.summary?.p95) + ' ms',
                  t('Trace requests with reported duration', '已上报耗时的 Trace 请求'),
                ],
              ]"
              :key="i"
            >
              <div>
                <span>{{ v[0] }}</span
                ><component
                  :is="[Activity, LayersIcon, BarChart3, Clock][i] || BarChart3"
                  :size="18"
                />
              </div>
              <strong>{{ v[1] }}</strong
              ><small>{{ v[2] }}</small>
            </article>
          </div>
          <div class="chart-grid">
            <section class="panel">
              <div class="panel-heading">
                <h2>{{ t("Activity over time", "调用趋势") }}</h2>
                <span class="legend"
                  ><i></i>{{ t("Requests per day (Trace)", "每日请求数（Trace）") }}</span
                >
              </div>
              <div v-if="daily.length" class="real-chart">
                <div class="chart-axis" aria-hidden="true">
                  <span v-for="tick in [1, 2 / 3, 1 / 3, 0]" :key="tick">{{
                    n(Math.round(maxCalls * tick))
                  }}</span>
                </div>
                <div v-for="d in daily" :key="d.day" class="chart-column">
                  <span>{{ n(d.calls) }}</span
                  ><i
                    :style="{
                      height: Math.max(3, (95 * d.calls) / maxCalls) + 'px',
                    }"
                    :title="d.day + ': ' + d.calls"
                  ></i
                  ><small>{{ String(d.day).slice(5, 10) }}</small>
                </div>
              </div>
              <div v-else class="chart-empty">
                <BarChart3 :size="34" /><span>{{
                  t(
                    "Your activity will appear after the first batch.",
                    "首批数据处理后将在这里显示趋势。",
                  )
                }}</span>
              </div>
            </section>
            <section class="panel">
              <div class="panel-heading">
                <h2>{{ t("Cost by model", "模型成本分布") }}</h2>
                <span class="muted">USD</span>
              </div>
              <div v-if="overview.models?.length" class="model-list">
                <div v-for="(m, i) in overview.models" :key="m.model">
                  <span
                    class="model-dot"
                    :style="{
                      background: ['#0052d9', '#19a6bb', '#7f72df'][
                        Number(i) % 3
                      ],
                    }"
                  ></span
                  ><span
                    >{{ m.model
                    }}<small
                      >{{ n(m.calls) }} {{ t("calls", "次调用") }}</small
                    ></span
                  ><b>{{ money(m.cost) }}</b>
                </div>
              </div>
              <div v-else class="chart-empty">
                <CoinsIcon /><span>{{
                  t(
                    "Add model rates to estimate costs.",
                    "配置模型费率后即可估算成本。",
                  )
                }}</span
                ><RouterLink to="/app/settings">{{
                  t("Configure pricing →", "配置价格 →")
                }}</RouterLink>
              </div>
            </section>
          </div>
          <section class="panel report-section activity-section">
            <div class="panel-heading">
              <h2>{{ t("Recent activity", "最近活动") }}</h2>
              <div class="activity-heading-actions">
                <a
                  class="btn"
                  :href="
                    '/api' +
                    path('/activity/export') +
                    '?format=xlsx&lang=' +
                    language + '&' + reportWindowQuery
                  "
                  >{{ t("Export Excel", "导出 Excel") }}</a
                >
                <a
                  class="btn"
                  :href="
                    '/api' +
                    path('/activity/export') +
                    '?format=pdf&lang=' +
                    language + '&' + reportWindowQuery
                  "
                  >{{ t("Export PDF", "导出 PDF") }}</a
                >
                <RouterLink class="btn" to="/app/traces">{{
                  t("View traces", "查看调用")
                }}</RouterLink>
              </div>
            </div>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{{ t("Object", "对象") }}</th>
                    <th>{{ t("Type", "类型") }}</th>
                    <th>{{ t("Model", "模型") }}</th>
                    <th>{{ t("Cost", "成本") }}</th>
                    <th>{{ t("Status", "状态") }}</th>
                    <th>{{ t("Time (UTC)", "时间 (UTC)") }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="o in recent" :key="o.type + ':' + o.object_id">
                    <td>
                      <RouterLink
                        v-if="o.trace_id"
                        :title="o.data.name || o.object_id"
                        :to="'/app/trace/' + o.trace_id"
                        >{{ o.data.name || o.object_id }}</RouterLink
                      ><span v-else>{{ o.data.name || o.object_id }}</span
                      ><small v-if="o.unmatched" class="amber">{{
                        t("Unmatched parent", "父对象尚未到达")
                      }}</small>
                    </td>
                    <td>
                      <span class="badge">{{ o.type }}</span>
                    </td>
                    <td>{{ o.data.model || "—" }}</td>
                    <td>
                      {{ o.type === "observation" ? money(o.cost) : "—" }}
                    </td>
                    <td>
                      <span
                        :class="[
                          'badge',
                          o.data.status === 'error' ? 'danger' : 'success',
                        ]"
                        >{{ o.data.status || o.data.valueType }}</span
                      >
                    </td>
                    <td>{{ date(o.started_at) }}</td>
                  </tr>
                  <tr v-if="!recent.length">
                    <td colspan="6" class="empty-cell">
                      {{
                        t(
                          "No events yet. Connect your application to get started.",
                          "暂无事件，请接入应用开始采集。",
                        )
                      }}
                      <RouterLink class="btn" to="/app/keys">{{
                        t("Connect →", "开始接入 →")
                      }}</RouterLink>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <QueryControls
              :page="activityPage"
              :size="activitySize"
              :total="activityTotal"
              :loading="loading"
              @change="activityTurn"
              @resize="
                activitySize = $event;
                activityTurn(0);
              "
            /></section
        ></template>
        <template v-if="page === 'traces' && selected"
          ><DateRangeFilter :busy="loading" @change="applyDates" /><p class="muted">{{date(reportWindow.from)}} → {{date(reportWindow.to)}} · {{t("Known model usage is aggregated by trace; unknown costs are excluded from the subtotal.","模型用量按调用链汇总；已知费用不含未知费用部分。")}}</p><section class="panel">
            <form
              class="filters"
              @submit.prevent="
                tracePage = 0;
                refresh(false);
              "
            >
              <div class="search-field">
                <Search :size="17" /><input
                  v-model="search"
                  :placeholder="
                    t('Search trace name or ID…', '搜索调用名称或 ID…')
                  "
                />
              </div>
              <select v-model="status">
                <option value="">{{ t("All statuses", "所有状态") }}</option>
                <option value="success">success</option>
                <option value="error">error</option>
                <option value="cancelled">cancelled</option></select
              ><button class="btn primary">{{ t("Search", "搜索") }}</button>
              <div class="query-export trace-export">
                <a
                  class="btn"
                  :href="reportExportUrl + '&format=xlsx&lang=' + language"
                  >{{ t("Export Excel", "导出 Excel") }}</a
                >
                <a
                  class="btn"
                  :href="reportExportUrl + '&format=pdf&lang=' + language"
                  >{{ t("Export PDF", "导出 PDF") }}</a
                >
              </div>
            </form>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{{ t("Name", "名称") }}</th>
                    <th>{{ t("Object ID", "对象 ID") }}</th>
                    <th><button type="button" class="sort-heading" @click="sortTraces('duration')">{{t("Duration", "耗时")}} {{traceSort==='duration'?(traceDirection==='desc'?'↓':'↑'):'↕'}}</button></th>
                    <th>{{t("Models","模型")}}</th><th><button type="button" class="sort-heading" @click="sortTraces('tokens')">Token {{traceSort==='tokens'?(traceDirection==='desc'?'↓':'↑'):'↕'}}</button></th><th><button type="button" class="sort-heading" @click="sortTraces('estimated_cost')">{{t("Known estimated cost","已知估算费用")}} {{traceSort==='estimated_cost'?(traceDirection==='desc'?'↓':'↑'):'↕'}}</button></th>
                    <th>{{ t("User / Customer", "用户 / 客户") }}</th>
                    <th>{{ t("Status", "状态") }}</th>
                    <th>{{ t("Started at (UTC)", "开始时间 (UTC)") }}</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="o in data.items || []" :key="o.object_id">
                    <td>
                      <strong>{{ o.data.name || "—" }}</strong>
                    </td>
                    <td class="mono">{{ o.object_id }}</td>
                    <td>{{ n(o.data.durationMs) }} ms</td><td>{{o.models||"—"}}</td><td>{{o.tokens==null?t("Unknown","未知"):n(o.tokens)}}</td><td>{{money(o.estimated_cost)}}<small class="muted">{{n(o.priced_calls)}}/{{n(o.generations)}} {{t("priced","条已定价")}}</small></td>
                    <td>
                      {{ o.data.userId || "—" }} /
                      {{ o.data.customerId || "—" }}
                    </td>
                    <td>
                      <span
                        :class="[
                          'badge',
                          o.data.status === 'error' ? 'danger' : 'success',
                        ]"
                        >{{ o.data.status }}</span
                      >
                    </td>
                    <td>{{ date(o.started_at) }}</td>
                    <td>
                      <RouterLink
                        class="btn"
                        :to="'/app/trace/' + o.trace_id"
                        >{{ t("View", "查看") }}</RouterLink
                      >
                    </td>
                  </tr>
                  <tr v-if="!data.items?.length">
                    <td colspan="10" class="empty-cell">
                      {{
                        t(
                          "No traces match these filters.",
                          "没有符合筛选条件的调用。",
                        )
                      }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <QueryControls
              :page="tracePage"
              :size="reportSize"
              @resize="
                reportSize = $event;
                tracePage = 0;
                refresh(false);
              "
              :total="data.total || 0"
              :loading="loading"
              @change="
                tracePage = $event;
                refresh(false);
              "
            /></section
        ></template>
        <template v-if="page === 'trace' && selected"
          ><RouterLink class="back-link" to="/app/traces"
            ><ArrowLeft :size="16" />{{
              t("All traces", "全部调用")
            }}</RouterLink
          >
          <div class="panel trace-title">
            <GitBranch :size="26" />
            <div>
              <h2>
                {{
                  traceObjects.find((o: any) => o.type === "trace")?.data
                    .name || t("Awaiting trace parent", "等待父 Trace")
                }}
              </h2>
              <code>{{ route.params.detail }}</code>
            </div>
            <span class="badge"
              >{{ traceObjects.length }} {{ t("objects", "个对象") }}</span
            >
          </div>
          <div class="insight-metrics"><article class="panel"><span>{{t('Request duration','请求总耗时')}}</span><strong>{{n(traceSummary.duration)}} ms</strong></article><article class="panel"><span>{{t('Model calls','模型调用数')}}</span><strong>{{n(traceSummary.calls)}}</strong></article><article class="panel"><span>{{t('Known tokens','已知 Token')}}</span><strong>{{n(traceSummary.tokens)}}</strong></article><article class="panel"><span>{{t('Known estimated cost','已知估算费用')}}</span><strong>{{money(traceSummary.cost)}}</strong><small>{{traceSummary.priced}}/{{traceSummary.calls}} {{t('model calls priced','条模型调用已定价')}}</small></article></div>
          <section class="panel">
            <div class="panel-heading">
              <h2>{{ t("Execution waterfall", "执行瀑布图") }}</h2>
              <span class="muted">ms · UTC</span>
            </div>
            <div
              class="waterfall"
              v-for="(o, i) in traceObjects.filter(
                (o: any) => o.type !== 'score',
              )"
              :key="o.object_id"
            >
              <span class="step-number">{{ Number(i) + 1 }}</span>
              <div>
                <b>{{ o.data.name || o.data.model || o.object_id }}</b
                ><small
                  >{{ objectLabel(o.type) }} ·
                  {{ o.data.observationType ? objectLabel(o.data.observationType) : o.data.status }}</small
                >
              </div>
              <div class="waterfall-track">
                <i
                  :style="{
                    marginLeft:
                      (100 * (new Date(o.started_at).getTime() - traceStart)) /
                        traceDuration +
                      '%',
                    width:
                      Math.max(
                        0.2,
                        (100 * (o.data.durationMs || 0)) / traceDuration,
                      ) + '%',
                    background:
                      o.data.status === 'error'
                        ? '#e25555'
                        : Number(i) % 2
                          ? '#67c9b2'
                          : '#74adfc',
                  }"
                ></i>
              </div>
              <span>{{ n(o.data.durationMs) }} ms</span>
            </div>
            <p v-if="!traceObjects.length" class="empty-cell">
              {{
                t(
                  "This trace is not available.",
                  "该调用不存在或已超过保留期。",
                )
              }}
            </p>
          </section>
          <section
            v-for="o in traceObjects"
            :key="o.object_id"
            class="panel detail-card"
          >
            <div class="panel-heading">
              <h2>{{ o.data.name || o.data.model || objectLabel(o.type) }}</h2>
              <span class="badge">{{t('Revision','修订版本')}} {{ o.revision }}</span>
            </div>
            <p v-if="o.data.observationType==='generation'">{{t('Model','模型')}}: {{o.data.model||'—'}} · {{t('Known estimated cost','已知估算费用')}}: {{money(o.cost)}}</p>
            <p v-if="o.data.observationType==='generation'" class="muted">{{t('Input / output / cached tokens','输入 / 输出 / 缓存 Token')}}: {{n(o.data.usage?.inputTokens)}} / {{n(o.data.usage?.outputTokens)}} / {{n(o.data.usage?.cachedInputTokens)}}</p>
            <p v-if="o.data.observationType==='generation' && o.cost==null" class="notice">{{costReason(o)}}</p>
            <p v-if="o.price_version" class="muted">{{t('Matched price version','命中价格版本')}}: {{o.price_version}} · {{t('Effective at','生效时间')}}: {{date(o.price_effective_at)}} · {{t('Input / output / cached USD per million tokens','输入 / 输出 / 缓存价格（美元/百万 Token）')}}: {{o.input_rate}} / {{o.output_rate}} / {{o.cached_rate}}</p>
            <details><summary>{{t('Reported fields','查看上报字段')}}</summary><dl>
              <template v-for="(value, key) in o.data" :key="key"
                ><dt :title="String(key)">{{ fieldLabel(String(key)) }}</dt>
                <dd>
                  <code v-if="typeof value === 'object'">{{
                    JSON.stringify(value)
                  }}</code
                  ><span v-else>{{ value ?? t("Unknown", "未知") }}</span>
                </dd></template
              >
              <dt>{{ t("Estimated cost", "估算成本") }}</dt>
              <dd>{{ o.type === "observation" ? money(o.cost) : "—" }}</dd>
            </dl></details>
          </section></template
        >
        <AdminProjects v-if="page === 'admin-projects' && accountReady" :refresh-token="loading" />
        <AnalyticsReport v-if="page === 'analytics' && selected" :project="selected" :refresh-token="data" />
        <template v-if="page === 'keys' && selected"
          ><section class="panel integration-banner">
            <CodeIcon />
            <div>
              <h2>{{ t("Connect your application", "接入你的应用") }}</h2>
              <p>
                {{
                  t(
                    "Project-scoped, write-only keys. Full keys appear once and are never stored in your browser.",
                    "密钥仅限当前项目写入，完整值只展示一次，不保存到浏览器。",
                  )
                }}
              </p>
            </div>
            <RouterLink class="btn" to="/docs"
              >{{ t("Quickstart", "快速开始") }}<ArrowUpRight :size="15"
            /></RouterLink>
          </section>
          <section v-if="createdKey" class="notice success">
            <b>{{
              t(
                "Copy your key now. It will not be shown again.",
                "请立即复制密钥，之后无法再次查看。",
              )
            }}</b>
            <div class="key-reveal">
              <code>{{ createdKey }}</code
              ><button class="btn" @click="copyKey">
                <component :is="copied ? Check : Copy" :size="16" /></button
              ><button class="btn" @click="createdKey = ''">
                {{ t("Dismiss", "关闭") }}
              </button>
            </div>
          </section>
          <section class="panel report-section keys-section">
            <div class="panel-heading">
              <h2>{{ t("API keys", "API 密钥") }}</h2>
              <form class="inline-form" @submit.prevent="createKey">
                <input
                  v-model="keyName"
                  required
                  maxlength="128"
                  :aria-label="t('Key name', '密钥名称')"
                /><button class="btn primary">
                  <Plus :size="16" />{{ t("Create key", "创建密钥") }}
                </button>
              </form>
            </div>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{{ t("Name", "名称") }}</th>
                    <th>{{ t("Prefix", "前缀") }}</th>
                    <th>{{ t("Status", "状态") }}</th>
                    <th>{{ t("Created", "创建时间") }}</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="k in Array.isArray(data) ? data : []" :key="k.id">
                    <td>{{ k.name }}</td>
                    <td>
                      <code>{{ k.prefix }}…</code>
                    </td>
                    <td>
                      <span :class="['badge', k.revoked_at ? '' : 'success']">{{
                        k.revoked_at
                          ? t("Revoked", "已撤销")
                          : t("Active", "有效")
                      }}</span>
                    </td>
                    <td>{{ date(k.created_at) }}</td>
                    <td>
                      <button
                        v-if="!k.revoked_at"
                        class="btn danger-text"
                        @click="revoke(k.id)"
                      >
                        {{ t("Revoke", "撤销") }}
                      </button>
                      <button
                        v-else
                        class="btn danger-text"
                        @click="removeEntry('keys', k.id)"
                      >
                        {{ t("Delete", "删除") }}
                      </button>
                    </td>
                  </tr>
                  <tr v-if="!data.length">
                    <td colspan="5" class="empty-cell">
                      {{
                        t(
                          "No keys yet. Create one to start sending events.",
                          "暂无密钥，创建后即可上报事件。",
                        )
                      }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div class="panel-foot">
              {{
                t(
                  "To rotate: create a new key, migrate your application, then revoke the previous key. Revocation takes effect immediately.",
                  "轮换方式：创建新密钥、迁移应用，再撤销旧密钥。撤销立即生效。",
                )
              }}
            </div>
          </section></template
        >
        <template v-if="page === 'alerts' && selected"
          ><section class="panel form-panel">
            <h2>{{ t("Create alert rule", "创建告警规则") }}</h2>
            <p>
              {{
                t(
                  "Checks the last hour every minute. Each object revision is notified once; new matches during the one-hour cooldown are merged.",
                  "每分钟检查最近一小时数据。同一对象版本只通知一次；一小时冷却期间的新异常合并通知。",
                )
              }}
            </p>
            <form
              class="alert-create-form"
              @submit.prevent="
                run(async () => {
                  await api(path('/alerts'), alert);
                  alert.name = '';
                  await refresh();
                })
              "
            >
              <label
                >{{ t("Rule name", "规则名称")
                }}<input
                  v-model="alert.name"
                  :placeholder="t('Enter rule name', '请输入规则名称')"
                  required
                  maxlength="128" /></label
              ><label
                >{{ t("Metric", "指标")
                }}<select v-model="alert.metric">
                  <option value="error_count">
                    {{ t("Error count", "错误数量") }}
                  </option>
                  <option value="cost_usd">
                    {{ t("Known cost (USD)", "已知费用 (USD)") }}
                  </option>
                  <option value="latency_ms">
                    {{ t("Maximum latency (ms)", "最大延迟 (ms)") }}
                  </option>
                </select></label
              ><label
                >{{ t("Threshold ≥", "阈值 ≥")
                }}<input
                  v-model="alert.threshold"
                  type="number"
                  min="0.000001"
                  step="any"
                  required /></label
              ><button class="btn primary">
                {{ t("Create rule", "创建规则") }}
              </button>
            </form>
          </section>
          <section class="panel table-wrap">
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{{ t("Rule", "规则") }}</th>
                    <th>{{ t("Metric", "指标") }}</th>
                    <th>{{ t("Threshold", "阈值") }}</th>
                    <th>{{ t("Last triggered", "最近触发") }}</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="r in data.rules || []">
                    <td>{{ r.name }}</td>
                    <td>{{ r.metric }}</td>
                    <td>{{ r.threshold }}</td>
                    <td>{{ date(r.last_triggered_at) }}</td>
                    <td class="table-actions">
                      <button
                        class="btn"
                        @click="
                          run(async () => {
                            await api(path('/alerts/' + r.id), {
                              enabled: !r.enabled,
                            });
                            await refresh();
                          })
                        "
                      >
                        {{
                          r.enabled ? t("Disable", "停用") : t("Enable", "启用")
                        }}
                      </button>
                      <button
                        class="btn danger-text"
                        @click="removeEntry('alerts', r.id)"
                      >
                        {{ t("Delete", "删除") }}
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p v-if="!data.rules?.length" class="empty-cell">
              {{ t("No rules configured.", "尚未配置规则。") }}
            </p>
          </section>
          <section class="panel report-section notifications-section">
            <div class="panel-heading">
              <h2>{{ t("Recent notifications", "最近告警") }}</h2>
            </div>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{{ t("Rule", "规则") }}</th>
                    <th>{{ t("Trigger value", "触发值") }}</th>
                    <th>{{ t("New matching objects", "新增匹配对象数") }}</th>
                    <th>
                      {{ t("Event time range (UTC)", "事件时间范围 (UTC)") }}
                    </th>
                    <th>
                      {{ t("Notification time (UTC)", "通知时间 (UTC)") }}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="e in data.events || []">
                    <td>{{ e.name }}</td>
                    <td>{{ e.value }}</td>
                    <td>{{ n(e.evidence_count) }}</td>
                    <td>
                      {{
                        e.first_started_at
                          ? date(e.first_started_at) +
                            " — " +
                            date(e.last_started_at)
                          : "—"
                      }}
                    </td>
                    <td>{{ date(e.created_at) }}</td>
                  </tr>
                  <tr v-if="!data.events?.length">
                    <td colspan="5" class="empty-cell">
                      {{ t("No alerts triggered.", "暂无触发记录。") }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section></template
        >
        <template v-if="page === 'billing'"
          ><div class="notice">
            {{
              t(
                "Platform units and model provider costs are separate. Plan changes and payment amounts are confirmed by your payment provider.",
                "平台 units 与模型供应商费用独立核算，套餐变更与付款金额以支付平台确认为准。",
              )
            }}
          </div>
          <BillingSubscription v-model:target-plan="changeTargetPlan" :data="data" :checkout-id="String(route.query.checkout || route.query.checkout_id || '')" @refresh="refresh(false)" />
          <div class="plans">
            <article
              v-for="(plan, i) in ['Free', 'Core', 'Pro']"
              :class="['panel', { featured: data.workspace?.plan === plan }]"
            >
              <h3>{{ plan }}</h3>
              <div class="price">
                ${{ [0, 29, 199][i] }}<small>/ {{ t("month", "月") }}</small>
              </div>
              <PlanFeatures :index="i" />
              <button
                class="btn full"
                :disabled="data.workspace?.plan === plan || plan === 'Free' || data.workspace?.payment_provider === 'creem'"
                @click="
                  run(async () => {
                    if (data.workspace?.subscription_id) {
                      if(data.workspace.payment_provider==='creem') { const r=await api('/billing/portal',{});navigate(r.url); }
                      else changeTargetPlan = plan;
                    }
                    else { const r = await api('/billing/checkout', { plan }); navigate(r.url); }
                  })
                "
              >
                {{
                  data.workspace?.plan === plan
                    ? t("Current plan", "当前套餐")
                    : t("Choose ", "选择 ") + plan
                }}
              </button>
            </article>
          </div>
          <div v-if="data.workspace?.subscription_id" class="section-heading">
            <p class="muted">{{ t("Manage payment methods, invoices, and subscription cancellation in the billing portal.", "在账单门户中管理付款方式、发票和取消订阅。") }}</p>
            <button class="btn" @click="run(async () => { const r = await api('/billing/portal', {}); navigate(r.url); })">{{ t("Manage subscription", "管理订阅") }}</button>
          </div>
          <div class="billing-details-grid">
            <section class="panel form-panel billing-usage-panel">
              <h2>{{ t("Monthly usage", "当月用量") }}</h2>
              <div class="billing-usage">
                <strong
                  >{{ n(data.workspace?.used_units) }}
                  <small>/ {{ n(data.included) }} units</small></strong
                ><span
                  >{{ t("Overage accrued", "已产生超额费") }}:
                  {{ money(data.overage) }}</span
                >
              </div>
              <p class="muted">
                {{ date(data.workspace?.period_start) }} →
                {{ date(data.workspace?.period_end) }}
              </p>
              <h2>{{ t("Overage policy", "超额策略") }}</h2>
              <p>
                {{
                  t(
                    "New objects stop by default. Core and Pro may opt into progressive overage starting at $8 per 100,000 units. Existing object updates do not add units.",
                    "默认停止接收新增对象。Core 和 Pro 可开启分段超额计费，起价 $8 / 100,000 units。更新已有对象不增加 units。",
                  )
                }}
              </p>
              <p v-if="data.workspace?.payment_provider==='creem'" class="notice">{{t('Automatic overage is not enabled for Creem subscriptions. Ingestion stops at the included quota.','Creem 订阅暂未开放自动超额计费，额度用完后停止新增接入。')}}</p>
              <form v-else class="billing-policy-form" @submit.prevent="saveOverage">
                <label class="check-label"
                  ><input
                    v-model="billing.enabled"
                    type="checkbox"
                    :disabled="data.workspace?.plan === 'Free'"
                  />{{
                    t("Enable automatic overage", "开启自动超额计费")
                  }}</label
                ><label class="narrow"
                  >{{ t("Monthly overage cap (USD)", "月度超额费用上限 (USD)")
                  }}<input
                    v-model="billing.cap"
                    type="number"
                    min="0"
                    step="0.01"
                    required /></label
                ><label class="check-label"
                  ><input
                    v-model="billing.consent"
                    type="checkbox"
                    :required="billing.enabled"
                  />{{
                    t(
                      "I authorize usage-based charges within this cap.",
                      "我授权在此上限内按实际使用量收费。",
                    )
                  }}</label
                ><button class="btn primary">
                  {{ t("Verify & save", "验证并保存") }}
                </button>
              </form>
            </section>
            <UsageCharges :workspace="data.workspace" />
            <BillingHistory kind="payments" :workspace="data.workspace" />
            <BillingHistory kind="adjustments" :workspace="data.workspace" />
          </div></template
        >
        <template v-if="page === 'settings' && selected"
          ><section class="panel form-panel">
            <h2>{{ t("Versioned model pricing", "模型价格版本") }}</h2>
            <p>
              {{
                t(
                  "Rates are USD per million tokens. Unknown prices stay unknown. New versions apply when processing new events or higher revisions; existing cost evidence is preserved. To correct an old call, configure a price effective on or before its start time and report the same object with a higher revision and complete usage; exact retries do not recalculate costs.",
                  "费率为每百万 Token 的美元价格。缺失价格保持未知；添加价格不会自动重算历史费用。修正历史调用时，先配置生效时间不晚于调用开始时间的价格，再以同一对象、更高 revision 和完整用量重新上报；原样重试不会重新计价。",
                )
              }}
            </p>
            <form
              class="price-version-form"
              @submit.prevent="
                run(async () => {
                  await api(path('/prices'), {
                    ...price,
                    effectiveAt: new Date(
                      price.effectiveAt + 'Z',
                    ).toISOString(),
                  });
                  await refresh();
                })
              "
            >
              <label
                >Provider<input
                  v-model="price.provider"
                  :placeholder="t('Enter Provider', '请输入Provider')"
                  required
                  maxlength="64" /></label
              ><label
                >Model<input
                  v-model="price.model"
                  :placeholder="t('Enter Model', '请输入Model')"
                  required
                  maxlength="256" /></label
              ><label
                >{{ t("Effective at (UTC)", "生效时间 (UTC)")
                }}<input
                  v-model="price.effectiveAt"
                  type="datetime-local"
                  required /></label
              ><label
                >{{ t("Input / 1M", "输入 / 百万")
                }}<input
                  v-model="price.inputRate"
                  type="number"
                  min="0"
                  step="any"
                  required /></label
              ><label
                >{{ t("Output / 1M", "输出 / 百万")
                }}<input
                  v-model="price.outputRate"
                  type="number"
                  min="0"
                  step="any"
                  required /></label
              ><label
                >{{ t("Cached input / 1M", "缓存输入 / 百万")
                }}<input
                  v-model="price.cachedRate"
                  type="number"
                  min="0"
                  step="any"
                  required /></label
              ><button class="btn primary">
                {{ t("Add price version", "添加价格版本") }}
              </button>
            </form>
          </section>
          <section class="panel table-wrap">
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Provider / model</th>
                    <th>{{ t("Input", "输入") }}</th>
                    <th>{{ t("Output", "输出") }}</th>
                    <th>{{ t("Cached", "缓存") }}</th>
                    <th>{{ t("Effective (UTC)", "生效时间 (UTC)") }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="p in Array.isArray(data) ? data : []">
                    <td>{{ p.provider }} / {{ p.model }}</td>
                    <td>{{ money(p.input_rate) }}</td>
                    <td>{{ money(p.output_rate) }}</td>
                    <td>{{ money(p.cached_rate) }}</td>
                    <td>{{ date(p.effective_at) }}</td>
                  </tr>
                  <tr v-if="!data.length">
                    <td colspan="5" class="empty-cell">
                      {{
                        t(
                          "No price versions. Model costs will be shown as unknown.",
                          "未配置价格版本，模型成本将显示为未知。",
                        )
                      }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
          <ProjectInfoEditor
            v-if="page === 'settings' && project"
            :project="project"
            @saved="refresh()"
          />
          <section class="panel form-panel danger-zone">
            <h2>{{ t("Delete project", "删除项目") }}</h2>
            <p>
              {{
                t(
                  "Keys are revoked immediately. Analytics detail is removed asynchronously; billing identities and audit records remain. Type the project name to confirm.",
                  "密钥立即撤销，分析明细异步移除；计费身份与审计记录保留。输入项目名称确认。",
                )
              }}
            </p>
            <form class="inline-form" @submit.prevent="deleteProject">
              <input
                v-model="deleteConfirm"
                :placeholder="project?.name"
                required
              /><button
                class="btn danger-button"
                :disabled="deleteConfirm !== project?.name"
              >
                {{ t("Verify & delete", "验证并删除") }}
              </button>
            </form>
          </section></template
        >
        <template v-if="page === 'account'"
          ><section class="panel form-panel">
            <h2>{{ t("Account details", "账号信息") }}</h2>
            <dl class="account-details-grid">
              <div>
                <dt>{{ t("Email", "邮箱") }}</dt>
                <dd>
                  {{ session?.email }}
                  <span class="badge success">{{
                    t("Verified", "已验证")
                  }}</span>
                </dd>
              </div>
              <div>
                <dt>GitHub</dt>
                <dd>
                  {{
                    session?.githubConnected
                      ? t("Connected", "已绑定")
                      : t("Not connected", "未绑定")
                  }}
                </dd>
              </div>
              <div>
                <dt>{{ t("Session", "会话") }}</dt>
                <dd>
                  {{
                    t("Valid until ", "有效期至 ") +
                    date(session?.expiresAt) +
                    " · " +
                    t("Revocable", "可撤销")
                  }}
                </dd>
              </div>
            </dl>
            <div class="inline-form">
              <RouterLink class="btn" to="/forgot-password">{{
                t("Reset password", "重置密码")
              }}</RouterLink
              ><button
                class="btn"
                :disabled="!accountReady || signingOut"
                @click="
                  verify(
                    session?.githubConnected ? 'github.unlink' : 'github.link',
                    'github',
                    async (proof) => {
                      const r = await api(
                        '/account/github/' +
                          (session?.githubConnected ? 'unlink' : 'link'),
                        proof,
                      );
                      if (r.url) navigate(r.url);
                    },
                  )
                "
              >
                {{
                  session?.githubConnected
                    ? t("Unlink GitHub", "解绑 GitHub")
                    : t("Link GitHub", "绑定 GitHub")
                }}</button
              ><button class="btn" :disabled="!accountReady || signingOut" @click="run(logout)">
                {{ t("Sign out", "退出登录") }}
              </button>
            </div>
            <p class="muted">
              {{
                t(
                  "Resetting your password revokes all existing sessions. Email changes require verification of both addresses.",
                  "重置密码将撤销所有已有会话。修改邮箱需要同时验证旧邮箱和新邮箱。",
                )
              }}
            </p>
          </section></template
        >
        <WorkspaceOverview
          v-if="page === 'workspace'"
          :data="data"
          @create="showCreate = true"
          @select="openProject"
        />

        <BatchHistory v-if="page === 'keys' && selected" :project="selected" />
        <BusinessInsights v-if="accountReady && selected && ['cost-insights','releases','weekly'].includes(page)" :key="page+selected" :project="selected" :mode="page" :refresh-token="loading" />
        <BudgetReminders v-if="accountReady && selected && page==='budgets'" :key="page+selected" :project="selected" :refresh-token="loading" />
        <ReliabilityInsights v-if="accountReady && selected && page==='reliability'" :key="page+selected" :project="selected" :refresh-token="loading" />
        <SupportMessages v-if="accountReady && ['messages', 'admin-messages'].includes(page)" :key="page" :admin="page === 'admin-messages'" :refresh-token="loading" />
        <AccountSecurity v-if="page === 'account'" :disabled="!accountReady || signingOut" />
        <template v-if="page === 'audit'"
          ><div class="audit-toolbar query-export trace-export">
            <h2>{{ t("Activity log", "操作记录") }}</h2>
            <a
              class="btn"
              :href="reportExportUrl + '&format=xlsx&lang=' + language"
              >{{ t("Export Excel", "导出 Excel") }}</a
            >
            <a
              class="btn"
              :href="reportExportUrl + '&format=pdf&lang=' + language"
              >{{ t("Export PDF", "导出 PDF") }}</a
            >
          </div>
          <section class="panel table-wrap">
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{{ t("Action", "操作") }}</th>
                    <th>{{ t("Target", "目标") }}</th>
                    <th>{{ t("Result", "结果") }}</th>
                    <th>IP</th>
                    <th>{{ t("Request ID", "请求 ID") }}</th>
                    <th>{{ t("Time (UTC)", "时间 (UTC)") }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="a in data.items || []">
                    <td>{{ a.action }}</td>
                    <td class="mono">{{ a.target }}</td>
                    <td>
                      <span
                        :class="['badge', a.success ? 'success' : 'danger']"
                        >{{
                          a.success ? t("successd", "成功") : t("Failed", "失败")
                        }}</span
                      >
                    </td>
                    <td>{{ a.ip || "—" }}</td>
                    <td class="mono">{{ a.request_id || "—" }}</td>
                    <td>{{ date(a.created_at) }}</td>
                  </tr>
                  <tr v-if="!data.items?.length">
                    <td colspan="6" class="empty-cell">
                      {{ t("No audit events.", "暂无审计记录。") }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <QueryControls
              :page="reportPage"
              :size="reportSize"
              @resize="
                reportSize = $event;
                reportPage = 0;
                refresh(false);
              "
              :total="data.total || 0"
              :loading="loading"
              @change="reportTurn"
            /></section
        ></template>
        <div class="console-footer">
          MeterTrail <span>·</span>
          {{
            t(
              "Model cost is an estimate. Missing usage is never treated as zero.",
              "模型成本为估算值，缺失用量不会按零处理。",
            )
          }}
        </div>
      </main>
    </div>
    <div v-if="showCreate" class="modal-backdrop">
      <section
        role="dialog"
        aria-modal="true"
        class="modal"
        aria-labelledby="create-title"
      >
        <button class="modal-close icon-button" @click="showCreate = false">
          <X :size="20" />
        </button>
        <div class="modal-icon"><FolderKanban :size="25" /></div>
        <h2 id="create-title">{{ t("Create a project", "创建项目") }}</h2>
        <p>
          {{
            t(
              "Keep each application’s data and API keys separate.",
              "为每个应用独立管理数据和密钥。",
            )
          }}
        </p>
        <form @submit.prevent="createProject">
          <label
            >{{ t("Project name", "项目名称")
            }}<input
              v-model="newProject.name"
              placeholder="resume-api"
              maxlength="128"
              required /></label
          ><label
            >{{ t("Environment", "环境")
            }}<input v-model="newProject.environment" maxlength="128" required
          /></label>
          <div v-if="error" class="notice error">{{ error }}</div>
          <button class="btn primary full">
            {{ t("Create project", "创建项目") }}
          </button>
        </form>
      </section>
    </div>
    <div v-if="verification.open" class="modal-backdrop">
      <section
        role="dialog"
        aria-modal="true"
        class="modal"
        aria-labelledby="verify-title"
      >
        <button
          class="modal-close icon-button"
          @click="verification.open = false"
        >
          <X :size="20" />
        </button>
        <div class="modal-icon"><ShieldCheck :size="26" /></div>
        <h2 id="verify-title">{{ t("Verify it’s you", "验证你的身份") }}</h2>
        <p>
          {{
            verification.challengeId
              ? t("Enter the code sent to", "请输入发送至")
              : t("Click Send code to receive a verification code at", "点击“发送验证码”，向")
          }}
          <b>{{ session?.email }}</b>
          {{
            verification.challengeId
              ? t("to authorize this change.", "的验证码以授权此次变更。")
              : t("to authorize this change.", "发送验证码以授权此次变更。")
          }}
        </p>
        <div class="verification-target">
          <code>{{ verification.action }}</code
          ><small>{{ verification.target }}</small>
        </div>
        <div v-if="verification.error" class="notice error">
          {{ verification.error }}
        </div>
        <form @submit.prevent="confirm">
          <label
            >{{ t("Verification code", "验证码")
            }}<input
              v-model="verification.code"
              class="code-input"
              inputmode="numeric"
              pattern="[0-9]{6}"
              maxlength="6"
              placeholder="000000"
              autocomplete="one-time-code"
              required /></label
          ><small>{{
            t(
              "Expires in 5 minutes. Maximum 5 attempts.",
              "5 分钟内有效，最多尝试 5 次。",
            )
          }}</small
          ><button
            class="btn primary full"
            :disabled="verification.busy || !verification.challengeId"
          >
            {{
              verification.busy
                ? t("Please wait…", "请稍候…")
                : t("Verify & confirm", "验证并确认")
            }}</button
          ><button
            class="text-button"
            type="button"
            :disabled="verification.busy"
            @click="sendCode"
          >
            {{
              verification.challengeId
                ? t("Resend code", "重新发送")
                : t("Send code", "发送验证码")
            }}
          </button>
        </form>
      </section>
    </div>
  </div>
</template>
<script lang="ts">
import {
  Layers as LayersIcon,
  Coins as CoinsIcon,
  Code2 as CodeIcon,
} from "lucide-vue-next";
export default { components: { LayersIcon, CoinsIcon, CodeIcon } };
</script>
