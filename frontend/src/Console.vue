<script setup lang="ts">
import { useToastNotice } from "./toast";
import {traceScores,latestTraceScore,observationScore} from "./trace-score";
import {traceColumnWidths} from "./trace-column-layout";
import TraceObservationsRow from "./TraceObservationsRow.vue";
import PublicModelPrices from "./PublicModelPrices.vue";
import BillingPriceRules from "./BillingPriceRules.vue";
import ModelPricePolicy from "./ModelPricePolicy.vue";
import RequestExplorer from "./RequestExplorer.vue";
import {usePageSize} from "./page-size";
import KeyQuickstart from "./KeyQuickstart.vue";
import IngestionNotice from "./IngestionNotice.vue";
import {readTraceView,traceViewKey} from "./trace-view-state";
import {traceObjectLabel} from "./trace-object-label";
import {inferWindowPolicy,refreshWindow,rollingWindow,type WindowPolicy} from "./date-range";
import DateRangeFilter from "./DateRangeFilter.vue";
import SupportMessages from "./SupportMessages.vue";
import AdminProjects from "./AdminProjects.vue";
import AdminPayments from "./AdminPayments.vue";
import {endedSubscription,planAction} from './billing-plan';
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
  CircleAlert,
  Coins,
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
function returnToRequests() {
 const previous=router.options.history.state.back;
 if(typeof previous==='string' && /^\/app\/(overview|traces)(?:[?#]|$)/.test(previous)) router.back();
 else void router.push('/app/traces?project='+encodeURIComponent(selected.value));
}
const requestExplorer = ref<InstanceType<typeof RequestExplorer>|null>(null);
const expandedTraces = ref<Record<string,boolean>>({});
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
  ["reliability", "Performance analysis", "性能分析", Activity],
  ["keys", "API key management", "密钥管理", KeyRound],
  ["alerts", "Alert configuration", "告警配置", Bell],
  ["billing", "Billing", "套餐与账单", CreditCard],
  ["settings", "Project settings", "项目设置", Settings],
  ["account", "Account security", "账号安全", ShieldCheck],
  ["audit", "Audit logs", "审计日志", ScrollText],
  ["messages", "Messages & feedback", "消息反馈", MessageSquare],
  ["admin-messages", "User messages", "用户消息", Inbox],
  ["admin-projects", "Project statistics", "项目统计", BarChart3],
  ["admin-payments", "Payments & refunds", "支付与退款", CreditCard],
] as const;
const navGroups = computed(() => [
  {
    en: "Projects",
    zh: "项目",
    items: nav.filter(
      (item) => !["account", "billing", "audit", "messages", "admin-messages", "admin-projects", "admin-payments"].includes(item[0]),
    ),
  },
  {
    en: "Account",
    zh: "账号",
    items: ["account", "billing", "audit", "messages"].map((id) =>
      nav.find((item) => item[0] === id)!,
    ),
  },
  ...(session.value?.isAdmin ? [{en: "Administrator", zh: "管理员", items: nav.filter((item) => ["admin-messages", "admin-projects", "admin-payments"].includes(item[0]))}] : []),
]);
const modelPricesLink = computed(() => ({path: "/app/model-prices", query: {project: selected.value, returnTo: route.fullPath}}));
const modelPricesBack = computed(() => {
 const origin = route.query.returnTo;
 return typeof origin === "string" && /^\/app\/(overview|settings)(?:[?#]|$)/.test(origin) ? origin : "/app/settings?project="+encodeURIComponent(selected.value);
});
const title = computed(() => {
  if (page.value === "model-prices") return t("Public model prices", "公共模型价格表");
  const item = nav.find((v) => v[0] === page.value);
  return item ? t(item[1], item[2]) : t("Trace detail", "调用详情");
});
const projects = ref<any[]>([]),
  selected = ref(localStorage.getItem("metertrail.project") || ""),
  loading = ref(true),
  contextReady = ref(false),
  signingOut = ref(false),
  error = ref(""),
  notice = useToastNotice(),
  data = ref<any>({}),
  overview = ref<any>({}),
  recent = ref<any[]>([]);
const reportPolicy=ref<WindowPolicy>({live:true,days:7});
const reportWindow=ref(rollingWindow(7));
const reportWindowQuery=computed(()=>new URLSearchParams(reportWindow.value).toString());
const observationFilter=ref('');
const traceSort=ref('started_at'),traceDirection=ref('desc'),costFilter=ref('all'),costReasonFilter=ref('');
let restoreTrace=true;
function restoreTraceView(){
 const key=traceViewKey(session.value?.id||'',selected.value),saved=readTraceView(sessionStorage,key);
 if(saved&&(saved.route===route.fullPath||Object.keys(route.query).every(k=>k==='project'))){observationFilter.value=saved.observation||'';search.value=saved.search;status.value=saved.status;costFilter.value=saved.cost;costReasonFilter.value=saved.reason;traceSort.value=saved.sort;traceDirection.value=saved.direction;tracePage.value=saved.page;reportSize.value=saved.size;reportWindow.value=saved.window;reportPolicy.value=saved.windowPolicy??inferWindowPolicy(saved.window);}
 else {observationFilter.value=['slow','error'].includes(String(route.query.observation))?String(route.query.observation):'';search.value='';status.value='';costFilter.value=['unknown','known'].includes(String(route.query.cost))?String(route.query.cost):'all';costReasonFilter.value=['usage_missing','model_missing','pricing_evidence_missing','price_missing','price_unavailable','billing_rule_missing','billing_rule_ambiguous','cache_rate_missing','cache_write_rate_missing','currency_unsupported'].includes(String(route.query.costReason))?String(route.query.costReason):'';tracePage.value=0;if(typeof route.query.from==='string'&&typeof route.query.to==='string'&&Number.isFinite(Date.parse(route.query.from))&&Date.parse(route.query.from)<Date.parse(route.query.to)) {reportWindow.value={from:route.query.from,to:route.query.to};reportPolicy.value={live:false};}}
 if(costReasonFilter.value)costFilter.value='unknown';
 restoreTrace=false;
}
function saveTraceView(){try{sessionStorage.setItem(traceViewKey(session.value?.id||'',selected.value),JSON.stringify({observation:observationFilter.value,search:search.value,status:status.value,cost:costFilter.value,reason:costReasonFilter.value,sort:traceSort.value,direction:traceDirection.value,page:tracePage.value,size:reportSize.value,window:reportWindow.value,windowPolicy:reportPolicy.value,route:route.fullPath}));}catch{}}

function refreshPage(){if(page.value==='traces'||page.value==='overview'){reportWindow.value=refreshWindow(reportWindow.value,reportPolicy.value);tracePage.value=0;}return refresh();}
async function copyRecordId(value:string){try{await navigator.clipboard.writeText(value);notice.value=t('ID copied.','ID 已复制。');}catch{error.value=t('Could not copy. Select the full ID from its tooltip or detail page.','复制失败，请从提示或详情页获取完整 ID。');}}
function applyDates(value:{from:string;to:string},policy?:WindowPolicy){reportWindow.value=value;reportPolicy.value=policy??inferWindowPolicy(value);tracePage.value=0;activityPage.value=0;refresh(false);}
function sortTraces(value:string){traceDirection.value=traceSort.value===value&&traceDirection.value==='desc'?'asc':'desc';traceSort.value=value;tracePage.value=0;refresh(false);}
const pageDescription=computed(()=>({'model-prices':t('Browse current public model rates and time-of-day pricing.','查看当前公共模型价格与分时费率。'),overview:t('Request performance, known model cost and usage for the selected period.','查看所选时间内的请求表现、已知模型费用和用量。'),traces:t('Find requests by name or status, then compare latency and model usage.','检索请求，比较耗时、模型用量与已知费用。'),trace:t('Inspect request steps, model usage and pricing evidence.','查看请求步骤、模型用量与计费依据。'),analytics:t('Compare usage by customer, user or feature.','按客户、用户或功能比较用量。'),'cost-insights':t('Find cost drivers and compare equal-length periods.','定位费用来源，对比等长时间段的变化。'),releases:t('Mark releases and compare before and after.','记录发布时间，对比修改前后的表现。'),weekly:t('Manage weekly email summaries and delivery history.','管理每周邮件摘要与发送记录。'),billing:t('Manage your plan, quota and payment records.','管理套餐、额度和付款记录。'),keys:t('Create and revoke project API keys.','创建与撤销项目接入密钥。'),settings:t('Manage project details and model price versions.','管理项目资料与模型价格版本。'),account:t('Manage sign-in methods and account security.','管理登录方式与账号安全。'),messages:t('Send feedback and follow replies.','提交问题与建议，查看回复。'),workspace:t('Manage projects and shared workspace usage.','管理项目与工作空间共享用量。'),budgets:t('Set model spending reminders.','设置模型费用预算提醒。'),reliability:t('Compare failure rates and latency by model or feature.','按模型或功能分析失败率与耗时排行。'),alerts:t('Configure notifications for project activity.','配置项目用量与异常通知。'),audit:t('Review account and workspace actions.','查看账号与工作空间操作记录。') } as Record<string,string>)[page.value]||t('Review and manage this section.','查看与管理本栏目内容。'));
function fieldLabel(key:string){return ({name:t('Name','名称'),status:t('Status','状态'),model:t('Model','模型'),provider:t('Provider','供应商'),usage:t('Token usage','Token 用量'),durationMs:t('Duration (ms)','耗时（毫秒）'),observationType:t('Step type','步骤类型'),userId:t('User','用户'),customerId:t('Customer','客户'),feature:t('Feature','功能'),metadata:t('Metadata','附加信息'),input:t('Input','输入'),output:t('Output','输出'),tags:t('Tags','标签'),error:t('Error','错误信息'),value:t('Value','评分值'),valueType:t('Value type','评分类型'),targetId:t('Target ID','关联对象 ID'),targetType:t('Target type','关联对象类型')} as Record<string,string>)[key]||key;}
function objectLabel(value:string){return ({trace:t('Request','请求'),observation:t('Step','步骤'),score:t('Score','评分'),generation:t('Model generation','模型生成'),tool:t('Tool','工具'),retrieval:t('Retrieval','检索')} as Record<string,string>)[value]||value;}
function costReason(o:any){return ({currency_unsupported:t('Currency conversion is not configured','未配置该币种的换算规则'),price_missing:t('No matching price at call time','调用发生时没有匹配的价格'),price_unavailable:t('Price withdrawn or unsupported','价格已撤回或规则不受支持'),billing_rule_missing:t('No matching batch, tier or regional rate','没有匹配的批量、等级或区域费率'),billing_rule_ambiguous:t('Time-of-day and special billing rules conflict','分时与特殊计费规则冲突'),cache_rate_missing:t('Cache read rate missing','缺少缓存读取费率'),cache_write_rate_missing:t('Cache write rate missing','缺少缓存写入费率'),usage_missing:t('Missing input, output or cached token usage; report zero cached tokens when there are none.','缺少输入、输出或缓存 Token 用量；没有缓存时请上报0。'),model_missing:t('Missing model or provider','缺少模型或供应商'),pricing_evidence_missing:t('No usable pricing evidence was recorded when this revision was processed. Check the model, provider and effective price date.','处理此修订时未记录可用计价证据，请核对模型、供应商与价格生效时间。')} as Record<string,string>)[o.cost_reason]||t('Unknown cost: reason needs confirmation','费用未知：原因待确认');}
const traceSummary=computed(()=>{const calls=traceObjects.value.filter((o:any)=>o.type==='observation'&&o.data.observationType==='generation');const priced=calls.filter((o:any)=>o.cost!=null);const usage=calls.filter((o:any)=>o.data.usage?.inputTokens!=null&&o.data.usage?.outputTokens!=null);return {calls:calls.length,priced:priced.length,cost:priced.length?priced.reduce((n:number,o:any)=>n+Number(o.cost),0):null,tokens:usage.length?usage.reduce((n:number,o:any)=>n+Number(o.data.usage.inputTokens)+Number(o.data.usage.outputTokens),0):null,duration:traceObjects.value.find((o:any)=>o.type==='trace')?.data.durationMs};});
const accountReady = computed(() => contextReady.value && !loading.value && !!session.value?.csrf);
const activityPage = ref(0),
  activitySize=usePageSize(),
  activityTotal = ref(0),
  reportPage = ref(0),
  auditSize = ref(10),
  reportSize=usePageSize();
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
      ...reportWindow.value, sort:traceSort.value,direction:traceDirection.value,cost:costFilter.value,costReason:costReasonFilter.value,observation:observationFilter.value,
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
  keyName = ref(""),
  copied = ref(false);
const changeTargetPlan = ref("");
const pricingStatus = ref<any>({});
const timeBandEnabled = ref(false);
const billingRules = ref<any[]>([]);
const timeBand = reactive({timezone:'Asia/Shanghai',start:'00:00',end:'08:00',inputRate:'',outputRate:'',cachedRate:''});
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
  if (!deletionReady.value) return;
  if (verification.busy || (!firstKeyCreation.value && !verification.challengeId)) return;
  if (firstKeyCreation.value && !keyName.value.trim()) {
    verification.error = t("Enter a key name.", "请输入密钥名称。");
    return;
  }
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
    if (firstKeyCreation.value && (e as any)?.code === "INVALID_VERIFICATION") keyCreationRequiresVerification.value = true;
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
  Promise.all([api("/projects"), api("/me"),api("/ingestion-status").catch(()=>null)]),
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
    const [projectList, user, ingestion] = await loadContext(
      force || page.value === "account",
    );
    current();
    projects.value = projectList;
    if(user.workspace)user.workspace.ingestion=ingestion;
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
    } else if (page.value === "workspace") {
      data.value = await request("/workspace");
      data.value.workspace.ingestion=ingestion;
    }
    else if (page.value === "audit")
      data.value = await request(
        "/reports/audit?page=" + reportPage.value + "&size=" + auditSize.value,
      );
    else if (["account", "messages", "admin-messages", "admin-projects", "admin-payments"].includes(page.value)) {
      /* Account is included in context. */
    } else if (selected.value) {
      if (page.value === "overview") {
        const o = await request(path("/overview")+"?"+new URLSearchParams(reportWindow.value));
        current(); overview.value = o;
      } else if (page.value === "traces") {
        if(restoreTrace)restoreTraceView();
        saveTraceView();
        data.value = await request(
          "/reports/traces" +
            "?" +
            new URLSearchParams({
              search: search.value,
              project: selected.value,
              status: status.value,
              page: String(tracePage.value),
              size: String(reportSize.value),
              ...reportWindow.value,sort:traceSort.value,direction:traceDirection.value,cost:costFilter.value,costReason:costReasonFilter.value,observation:observationFilter.value,
            }),
        );
      } else if (page.value === "trace")
        data.value = await request(path("/traces/" + route.params.detail));
      else if (page.value === "analytics") data.value = {};
      else if (page.value === "keys") data.value = await request(path("/keys"));
      else if (page.value === "alerts")
        data.value = await request(path("/alerts"));
      else if (page.value === "model-prices") {
        pricingStatus.value = await request(path("/pricing-status"));
        data.value = {};
      }
      else if (page.value === "settings") {
        const [prices, pricing] = await Promise.all([request(path("/prices")),request(path("/pricing-status"))]);
        data.value = prices; pricingStatus.value = pricing;
      }
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
watch([page,selected], () => { expandedTraces.value = {}; });
watch(
  () => route.fullPath,
  () => {
    reportPage.value = 0;
    restoreTrace=true;
    data.value = {};
    notice.value = "";
    createdKey.value = "";
    refresh(false);
  },
);
async function changeProject() {
  restoreTrace=true;
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
const keyCreationRequiresVerification = ref(true);
const firstKeyCreation = computed(() => verification.action === "key.create" && !keyCreationRequiresVerification.value);
const verificationKeyName = ref("");
let verificationKeyProject = "";
async function sendVerificationCode() {
  if (!deletionReady.value) return;
  if (verification.action === "key.create") {
    if (!keyName.value.trim()) {
      verification.error = t("Enter a key name.", "请输入密钥名称。");
      return;
    }
    verification.target = verificationKeyProject + ":" + keyName.value;
  }
  await sendCode();
}
async function createKey() {
  const p = selected.value;
  try {
    const policy = await api("/projects/" + p + "/keys/creation-policy");
    keyCreationRequiresVerification.value = policy.verificationRequired !== false;
  } catch (e) {
    error.value = errorText(e);
    return;
  }
  verificationKeyProject = p;
  await verify("key.create", p + ":" + keyName.value, async (proof) => {
    const name = keyName.value;
    const result = await api("/projects/" + p + "/keys", { ...proof, name });
    createdKey.value = result.key;
  });
}
async function revoke(key: string, name: string) {
  const p = selected.value;
  verificationKeyName.value = name;
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
const deletionProject = ref<{id: string; name: string} | null>(null);
const deletionReady = computed(() => verification.action !== 'project.delete' || deleteConfirm.value === 'delete');
async function deleteProject(project: {id: string; name: string}) {
  deletionProject.value = {...project};
  deleteConfirm.value = '';
  const id = project.id;
  await verify("project.delete", id, async (proof) => {
    if (!deletionReady.value) return;
    await api("/projects/" + id + "/delete", { ...proof, confirm: deleteConfirm.value });
    if (selected.value === id) selected.value = "";
    await router.push("/app/workspace");
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
      (session.value?.workspace.plan === "Free" ? 50000 : session.value?.workspace.plan === "Pro" ? 1000000 : 100000),
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
        <IngestionNotice :state="session?.workspace?.ingestion" :pending="loading" compact /><RouterLink to="/docs"
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
                !['billing', 'audit', 'account', 'workspace', 'messages', 'admin-messages', 'admin-projects', 'admin-payments'].includes(page) &&
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
              @click="refreshPage()"
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
        <IngestionNotice v-if="contextReady && !loading && session?.workspace?.ingestion?.can_accept_new===false && page!=='billing'" :state="session.workspace.ingestion" prominent />
        <div v-if="error" class="notice error" role="alert">{{ error }}</div>

        <div
          v-if="
            !loading &&
            !selected &&
            !['billing', 'audit', 'account', 'workspace', 'messages', 'admin-messages', 'admin-projects', 'admin-payments'].includes(page)
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
          ><DateRangeFilter :busy="loading" :value="reportWindow" @change="applyDates" />
          <div class="metrics overview-metrics">
            <article
              class="panel metric overview-stat"
              :class="'overview-stat-'+i"
              v-for="(v, i) in [
                [
                  t('Requests', '请求数'),
                  n(overview.summary?.traces),
                  t('Application requests (Trace)', '应用请求（Trace）'),
                ],
                [
                  t('Slow calls', '慢调用数'),
                  n(overview.summary?.slow_requests),
                  t('Observation · single call ≥ 5 seconds', 'Observation · 单次调用耗时 ≥5 秒'),
                ],
                [
                  t('Failed calls', '失败调用数'),
                  n(overview.summary?.failed_requests),
                  t('Observation · single call status is error', 'Observation · 单次调用状态为 error'),
                ],
                [
                  t('Total tokens', '总 Token'),
                  n(overview.summary?.tokens),
                  t('Known input + output', '已知输入 + 输出'),
                ],
                [
                  t('Known estimated model cost', '已知模型估算费用'),
                  overview.summary?.cost==null?'—':money(overview.summary?.cost),
                  '',
                ],
              ]"
              :key="i"
            >
              <span class="overview-stat-icon" aria-hidden="true"><component
                  :is="[Activity, Clock, CircleAlert, LayersIcon, Coins][i]"
                  :size="24"
                /></span>
              <div class="overview-stat-copy"><span>{{ v[0] }}</span>
              <strong><a v-if="i<3" href="#overview-requests" class="overview-metric-link" @click.prevent="requestExplorer?.setFilter(['all','slow','error'][i])">{{v[1]}}</a><template v-else>{{ v[1] }}</template></strong
              ><small>{{ v[2] }}</small><RouterLink v-if="i===4" :to="modelPricesLink">{{t('Review model rates','查看模型价格')}}</RouterLink></div>
            </article>
          </div>
          <RequestExplorer ref="requestExplorer" :project="selected" :window="reportWindow" :refresh-token="loading" /></template>
        <template v-if="page === 'traces' && selected"
          ><DateRangeFilter class="trace-query-toolbar" :busy="loading" :value="reportWindow" @change="applyDates">
            <div class="trace-query-fields">
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
              ><select v-model="costFilter" :aria-label="t('Cost completeness','费用完整性')" @change="costFilter!=='unknown'&&(costReasonFilter='')"><option value="all">{{t('All costs','全部费用状态')}}</option><option value="unknown">{{t('Has unknown cost','含未知费用')}}</option><option value="known">{{t('Fully priced model calls','模型调用全部已定价')}}</option></select><select v-model="observationFilter" :aria-label="t('Call filter','调用筛选')"><option value="">{{t('All calls','全部调用')}}</option><option value="slow">{{t('Slow calls (≥ 5 s)','慢调用（≥ 5 秒）')}}</option><option value="error">{{t('Failed calls','失败调用')}}</option></select><select v-if="costFilter==='unknown'" v-model="costReasonFilter" :aria-label="t('Unknown cost reason','未知费用原因')"><option value="">{{t('All reasons','全部原因')}}</option><option value="usage_missing">{{t('Usage missing','缺少用量')}}</option><option value="model_missing">{{t('Model or provider missing','缺少模型或供应商')}}</option><option v-for="reason in ['price_missing','price_unavailable','billing_rule_missing','billing_rule_ambiguous','cache_rate_missing','cache_write_rate_missing','currency_unsupported']" :value="reason">{{costReason({cost_reason:reason})}}</option><option value="pricing_evidence_missing">{{t('Pricing evidence missing','缺少计价证据')}}</option></select>
            </div>
            <template #actions>
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
            </template>
          </DateRangeFilter><section class="panel">
            <div class="table-wrap">
              <table class="traces-table"><colgroup><col v-for="(width,i) in traceColumnWidths" :key="i" :style="{width:width+'%'}" /></colgroup>
                <thead>
                  <tr>
                    <th :aria-label="t('Expand observations','展开 Observation')"></th><th>{{ t("Name", "名称") }}</th><th>eventId</th>
                    <th>{{ t("Object ID", "对象 ID") }}</th>
                    <th><button type="button" class="sort-heading" @click="sortTraces('duration')">{{t("Duration", "耗时")}} {{traceSort==='duration'?(traceDirection==='desc'?'↓':'↑'):'↕'}}</button></th>
                    <th><button type="button" class="sort-heading" @click="sortTraces('tokens')">Token {{traceSort==='tokens'?(traceDirection==='desc'?'↓':'↑'):'↕'}}</button></th><th><button type="button" class="sort-heading" @click="sortTraces('estimated_cost')">{{t("Cost","费用")}} {{traceSort==='estimated_cost'?(traceDirection==='desc'?'↓':'↑'):'↕'}}</button></th>
                    <th>{{ t("User / Customer", "用户 / 客户") }}</th>
                    <th>{{ t("Status", "状态") }}</th>
                    <th>{{t("Score","打分")}}</th>
                    <th>{{ t("Started at (UTC)", "开始时间 (UTC)") }}</th>
                    <th>{{t("Actions","操作")}}</th>
                  </tr>
                </thead>
                <tbody>
                  <template v-for="o in data.items || []" :key="selected+o.object_id"><tr :class="{'trace-row-expanded':expandedTraces[selected+o.object_id]}">
                    <td><button type="button" class="trace-expand" :aria-expanded="!!expandedTraces[selected+o.object_id]" :aria-label="t('Toggle observations','展开或收起 Observation')" @click="expandedTraces[selected+o.object_id]=!expandedTraces[selected+o.object_id]">{{expandedTraces[selected+o.object_id]?'▾':'▸'}}</button></td>
                    <td :data-label="t('Name','名称')">
                      <strong>{{ o.data.name || "—" }}</strong>
                    </td>
                    <td data-label="eventId"><span class="record-id"><code>{{o.event_id||'—'}}</code><button v-if="o.event_id" type="button" class="copy-id" :aria-label="t('Copy event ID','复制事件 ID')" @click="copyRecordId(o.event_id)"><Copy :size="14" /></button></span></td>
                    <td :data-label="t('Object ID','对象 ID')"><span class="record-id"><code :title="o.object_id">{{o.object_id}}</code><button type="button" class="copy-id" :aria-label="t('Copy full ID','复制完整 ID')" @click="copyRecordId(o.object_id)"><Copy :size="14" /></button></span></td>
                    <td :data-label="t('Duration','耗时')">{{ n(o.data.durationMs) }} ms</td><td data-label="Token" :title="o.tokens==null?t('Token usage not reported','未上报 Token 用量'):''">{{o.tokens==null?'—':n(o.tokens)}}</td><td :data-label="t('Known cost','已知费用')"><span :title="o.estimated_cost==null?t('No priced model calls; check usage and model rates.','无已定价模型调用，请检查用量和模型价格。'):''">{{o.estimated_cost==null?'—':money(o.estimated_cost)}}</span><small class="muted">{{n(o.priced_calls)}}/{{n(o.generations)}} {{t("priced","条已定价")}}</small></td>
                    <td :data-label="t('User / Customer','用户 / 客户')">
                      {{ o.data.userId || "—" }} /
                      {{ o.data.customerId || "—" }}
                    </td>
                    <td :data-label="t('Status','状态')">
                      <span
                        :class="[
                          'badge',
                          o.data.status === 'error' ? 'danger' : 'success',
                        ]"
                        >{{ o.data.status }}</span
                      >
                    </td>
                    <td :data-label="t('Score','打分')" :title="o.score_reported?t('Latest Score reported for this Trace','关联当前 Trace 的最新 Score 评分'):t('No Score reported; displaying 0','未上报 Score，默认显示 0')">{{o.score??0}}</td>
                    <td :data-label="t('Started at (UTC)','开始时间（UTC）')">{{ date(o.started_at) }}</td>
                    <td :data-label="t('Actions','操作')">
                      <RouterLink
                        class="btn"
                        :to="'/app/trace/' + o.trace_id + '?project='+selected"
                        >{{ t("View full request", "查看完整请求") }}</RouterLink
                      >
                    </td>
                  </tr>
                  <TraceObservationsRow v-if="expandedTraces[selected+o.object_id]" :project="selected" :trace-id="o.trace_id" /></template>
                  <tr v-if="!data.items?.length">
                    <td colspan="11" class="empty-cell">
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
          ><RouterLink class="back-link" :to="'/app/traces?project='+selected" @click.prevent="returnToRequests"
            ><ArrowLeft :size="16" />{{
              t("Back", "返回")
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
              >{{ traceObjects.length }} {{ traceObjects.length===1?t("object", "个对象"):t("objects", "个对象") }}</span
            >
          </div>
          <div class="insight-metrics trace-detail-metrics"><article class="panel trace-metric-duration"><Clock :size="20"/><span>{{t('Request duration','请求总耗时')}}</span><strong>{{n(traceSummary.duration)}} ms</strong></article><article class="panel trace-metric-calls"><GitBranch :size="20"/><span>{{t('Model calls','模型调用数')}}</span><strong>{{n(traceSummary.calls)}}</strong></article><article class="panel trace-metric-tokens"><Activity :size="20"/><span>{{t('Known tokens','已知 Token')}}</span><strong>{{n(traceSummary.tokens)}}</strong></article><article class="panel trace-metric-cost"><Coins :size="20"/><span>{{t('Known estimated cost','已知估算费用')}}</span><strong>{{money(traceSummary.cost)}}</strong><small>{{traceSummary.priced}}/{{traceSummary.calls}} {{t('model calls priced','条模型调用已定价')}}</small></article></div>
          <section class="panel trace-waterfall">
            <div class="panel-heading">
              <h2>{{ t("Execution waterfall", "执行瀑布图") }}</h2>
              <span class="muted">ms · UTC</span>
            </div>
            <div
              class="waterfall"
              v-for="(o, i) in traceObjects"
              :key="o.object_id"
            >
              <span class="step-number">{{ Number(i) + 1 }}</span>
              <div>
                <div class="waterfall-object-title"><b>{{ traceObjectLabel(o) }}</b><span class="badge">{{o.type==='trace'?'Trace':o.type==='observation'?'Observation':'Score'}}</span></div>
              </div>
              <div v-if="o.type!=='score'" class="waterfall-track">
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
              <div class="waterfall-result"><span v-if="o.type!=='score'">{{ n(o.data.durationMs) }} ms</span><span class="badge" :title="t('Latest reported Score for this object; defaults to 0','关联此对象的最新 Score；未评分显示 0')">{{t('Score','打分')}}：{{o.type==='score'?o.data.value:o.type==='trace'?latestTraceScore(traceObjects):observationScore(traceObjects,o.object_id).value}}</span></div>
              <div class="waterfall-details">
                                <code class="waterfall-id-line">{{t('Event ID','事件 ID')}}：{{o.event_id||t('Not retained','未保留')}}</code>
                <code class="waterfall-id-line">{{t('Object ID','对象 ID')}}：{{ o.object_id }}</code>
<div class="waterfall-event-meta"><span>{{t('Event time','事件时间')}}: {{date(o.started_at)}}</span><span>{{t('Status','状态')}}: <span class="badge" :class="o.data.status==='success'?'success':o.data.status==='error'?'danger':''">{{o.data.status||'—'}}</span></span></div>
            <p v-if="o.type==='score'" class="trace-score-value">{{o.data.name}}：{{o.data.value}}</p><p v-if="o.data.observationType==='generation'">{{t('Known estimated cost','已知估算费用')}}: {{money(o.cost)}}</p>
            <p v-if="o.data.observationType==='generation'" class="muted">{{t('Input / output / cached tokens','输入 / 输出 / 缓存 Token')}}: {{n(o.data.usage?.inputTokens)}} / {{n(o.data.usage?.outputTokens)}} / {{n(o.data.usage?.cachedInputTokens)}}</p>
            <p v-if="o.data.observationType==='generation' && o.cost==null" class="notice">{{costReason(o)}}</p>


              </div>
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
</template
        >
        <AdminProjects v-if="page === 'admin-projects' && accountReady" :refresh-token="loading" />
        <AdminPayments v-if="page === 'admin-payments' && accountReady" :refresh-token="loading" />
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
            <KeyQuickstart :api-key="createdKey" />
          </section>
          <section v-if="Array.isArray(data) && !data.some((key:any)=>!key.revoked_at) && !createdKey" class="notice"><strong>{{t('Create an active key to connect your application','创建有效密钥以接入应用')}}</strong><p>{{t('Click Create key and enter a name in the dialog. After creation, copy your key and run the example to send your first request. Revoked keys cannot send data.','点击创建密钥，在弹窗中填写名称；创建后复制密钥，运行示例发送第一条数据。已撤销的密钥不能上报。')}}</p></section>
          <section class="panel report-section keys-section">
            <div class="panel-heading">
              <h2>{{ t("API keys", "API 密钥") }}</h2>
              <button class="btn primary" type="button" @click="createKey">
                <Plus :size="16" />{{ t("Create key", "创建密钥") }}
              </button>
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
                        @click="revoke(k.id, k.name)"
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

          </section></template
        >
        <template v-if="page === 'alerts' && selected"
          ><section class="panel form-panel">
            <h2>{{ t("Create alert rule", "创建告警规则") }}</h2>
            <p>{{t('Notifications are emailed to the workspace owner’s account email. Recipients and Webhook/Slack channels cannot currently be customized.','通知发送到工作空间所有者的账号邮箱，当前不支持自定义收件人或 Webhook/Slack 渠道。')}}</p>
            <details class="notification-rules"><summary>{{t('Metrics and notification scope','指标口径与通知范围')}}</summary><p>{{t('Error count counts error objects in the last hour; latency compares the maximum matching object duration in milliseconds; cost sums positive known object costs in USD. Rules examine both Trace and Observation records, so request and step records may both match. This differs from Overview’s slow/failure counts, which count individual Observations only.','错误数量统计最近一小时状态为 error 的对象数；耗时按匹配对象的最大毫秒数比较；费用累加对象的已知正费用（USD）。规则同时检查 Trace 和 Observation，请求与步骤可能分别匹配；这与概览只统计单次 Observation 的慢调用、失败调用口径不同。')}}</p><p>{{t('For calendar-month model spending and forecast warnings, use Budget reminders. Neither feature charges your payment method or stops ingestion.','按自然月的模型费用与预测提醒请使用“预算提醒”。两种提醒都不会扣款或停止接入。')}} <RouterLink to="/app/budgets">{{t('Budget reminders','预算提醒')}}</RouterLink></p></details>
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
        <template v-if="page === 'billing'">
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
                :disabled="['current','disabled'].includes(planAction(data.workspace,plan))"
                @click="
                  run(async () => {
                    if (planAction(data.workspace,plan)==='change') {
                      if(data.workspace.payment_provider==='creem') { const r=await api('/billing/portal',{});navigate(r.url); }
                      else changeTargetPlan = plan;
                    }
                    else { const r = await api('/billing/checkout', { plan }); navigate(r.url); }
                  })
                "
              >
                {{
                  planAction(data.workspace,plan)==='current'
                    ? t("Current plan", "当前套餐")
                    : endedSubscription(data.workspace) && (data.workspace?.retention_plan||data.workspace?.plan)===plan ? t('Reopen ','重新开通 ') + plan : t("Choose ", "选择 ") + plan
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
        <template v-if="page === 'model-prices' && selected">
          <RouterLink class="back-link" :to="modelPricesBack"><ArrowLeft :size="16" />{{t('Back','返回')}}</RouterLink>
          <PublicModelPrices :project="selected" :refresh-key="data" :public-pricing="pricingStatus.public_pricing" />
        </template>
        <template v-if="page === 'settings' && selected">
          <ProjectInfoEditor v-if="project" :project="project" @saved="refresh()" />
          <section class="panel form-panel">
            <h2>{{ t("Public model pricing", "公共模型价格") }}</h2>
            <RouterLink :to="modelPricesLink">{{t("View model prices", "查看模型价格")}}</RouterLink>
            <ModelPricePolicy :status="pricingStatus" @change="(enabled)=>run(async()=>{await api(path('/pricing-policy'),{publicPricing:enabled});await refresh()})" />
          </section>

          <div class="custom-pricing-grid">
          <section class="panel form-panel">
            <h2>{{t('Custom model price configuration','自定义模型价格配置')}}</h2>
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
                    billingRules: billingRules,
                    timeBands: timeBandEnabled ? [{...timeBand,inputRate:Number(timeBand.inputRate),outputRate:Number(timeBand.outputRate),cachedRate:Number(timeBand.cachedRate)}] : [],
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
              >
              <label class="price-band-toggle"><input type="checkbox" v-model="timeBandEnabled" />{{t('Add a time-of-day rate','添加分时费率')}}</label>
              <template v-if="timeBandEnabled">
                <label>{{t('Time zone','时区')}}<input v-model="timeBand.timezone" required placeholder="Asia/Shanghai" /></label>
                <label>{{t('Start (inclusive)','开始（包含）')}}<input type="time" v-model="timeBand.start" required /></label>
                <label>{{t('End (exclusive)','结束（不含）')}}<input type="time" v-model="timeBand.end" required /></label>
                <label>{{t('Period input / 1M','时段输入 / 百万')}}<input type="number" min="0" step="any" v-model="timeBand.inputRate" required /></label>
                <label>{{t('Period output / 1M','时段输出 / 百万')}}<input type="number" min="0" step="any" v-model="timeBand.outputRate" required /></label>
                <label>{{t('Period cached / 1M','时段缓存 / 百万')}}<input type="number" min="0" step="any" v-model="timeBand.cachedRate" required /></label>
                <p class="price-band-toggle muted">{{t('This period uses its own rates; other times use the base rates above. Overnight periods are supported.','此时段使用独立费率，其他时间使用上方基础费率；支持跨午夜时段。')}}</p>
              </template>
              <BillingPriceRules v-model="billingRules" />
              <button class="btn primary">
                {{ t("Add price version", "添加价格版本") }}
              </button>
            </form>
          </section>
          <section class="panel form-panel custom-price-list">
            <h2>{{t('Custom model price list','自定义模型价格列表')}}</h2>
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
                    <td>{{ p.provider }} / {{ p.model }}<small v-for="band in p.time_bands" class="muted" style="display:block">{{band.timezone}} {{band.start}}–{{band.end}} · {{band.inputRate}} / {{band.outputRate}} / {{band.cachedRate}}</small><small v-for="rule in p.billing_rules||[]" class="muted" style="display:block">{{rule.batch?'Batch':'Standard'}} · {{rule.serviceTier}} · {{rule.region}} · {{t('Input ≥','输入 ≥')}} {{rule.minInputTokens}} · {{rule.inputRate}} / {{rule.outputRate}} / {{rule.cachedRate}} / {{rule.cacheWrite5mRate??'—'}} / {{rule.cacheWrite1hRate??'—'}}</small></td>
                    <td>{{ money(p.input_rate) }}</td>
                    <td>{{ money(p.output_rate) }}</td>
                    <td>{{ money(p.cached_rate) }}</td>
                    <td>{{ date(p.effective_at) }}</td>
                  </tr>
                  <tr v-if="!data.length">
                    <td colspan="5" class="empty-cell">
                      {{
                        t(
                          "No custom overrides. Recognized public models use the public catalogue.",
                          "暂无自定义价格，已收录的公共模型自动使用公共价格目录。",
                        )
                      }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
          </div>
          </template
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
          v-if="page === 'workspace' && !loading && data.workspace"
          :data="data"
          @create="showCreate = true"
          @select="openProject"
          @delete="deleteProject"
        />

        <BusinessInsights v-if="accountReady && selected && ['cost-insights','releases','weekly'].includes(page)" :key="page+selected" :project="selected" :mode="page" :refresh-token="loading" />
        <BudgetReminders v-if="accountReady && selected && page==='budgets'" :key="page+selected" :project="selected" :refresh-token="loading" />
        <ReliabilityInsights v-if="accountReady && selected && page==='reliability'" :key="page+selected" :project="selected" :refresh-token="loading" />
        <SupportMessages v-if="accountReady && ['messages', 'admin-messages'].includes(page)" :key="page" :admin="page === 'admin-messages'" :refresh-token="loading" />
        <AccountSecurity v-if="page === 'account'" :disabled="!accountReady || signingOut" />
        <template v-if="page === 'audit'"
          ><p class="muted">{{t('Audit records cover recorded account and workspace operations, not every model event. They are separate from plan analytical retention and currently have no fixed automatic expiry. Deleting a project/account retains audit records; this is not a permanent-storage guarantee. Export records you need.','审计记录覆盖已记录的账号与工作空间操作，不是每条模型事件；独立于套餐分析明细保留窗口，目前无固定自动到期清理期限。删除项目或账号仍保留审计记录，但不保证永久保存，请导出所需记录。')}} <RouterLink to="/security#data-handling">{{t('Data processing and retention','数据处理与保留说明')}}</RouterLink></p><div class="audit-toolbar query-export trace-export">
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
              :size="auditSize"
              @resize="
                auditSize = $event;
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
        <h2 id="verify-title">{{ firstKeyCreation ? t("Create key", "创建密钥") : t("Verify it’s you", "验证你的身份") }}</h2>
        <p v-if="!firstKeyCreation">
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
        <div v-if="verification.action === 'key.create'" class="verification-target">
          <strong>{{ t('Create key', '创建密钥') }}</strong>
          <label>{{ t('Key name', '密钥名称') }}<input v-model="keyName" :placeholder="t('Enter a key name', '请输入密钥名称')" required maxlength="128" :disabled="verification.busy || !!verification.challengeId" /></label>
        </div>
        <div v-else-if="verification.action === 'key.revoke'" class="verification-target">
          <strong>{{ t('Revoke key', '撤销密钥') }}</strong>
          <p>{{ t('Key name: ', '密钥名称：') }}{{ verificationKeyName }}</p>
          <small>{{ t('Programs using this key will no longer be able to send data after revocation.', '撤销后，使用该密钥的程序将无法继续上报。') }}</small>
        </div>
        <div v-else-if="verification.action === 'project.delete'" class="verification-target">
          <strong>{{ t('Delete project', '删除项目') }}：{{ deletionProject?.name }}</strong>
          <p>{{ t('Keys stop working immediately. Analytics data is deleted asynchronously. Billing and audit records are retained. This cannot be undone.', '密钥立即失效，分析数据将异步删除，账单和审计记录保留。此操作无法撤销。') }}</p>
          <label>{{ t('Type delete to confirm', '输入 delete 确认') }}<input v-model="deleteConfirm" placeholder="delete" :disabled="verification.busy" autocomplete="off" /></label>
        </div>
        <div v-else class="verification-target">
          <code>{{ verification.action }}</code
          ><small>{{ verification.target }}</small>
        </div>
        <div v-if="verification.error" class="notice error">
          {{ verification.error }}
        </div>
        <form @submit.prevent="confirm">
          <label
            v-if="!firstKeyCreation">{{ t("Verification code", "验证码")
            }}<input
              v-model="verification.code"
              class="code-input"
              inputmode="numeric"
              pattern="[0-9]{6}"
              maxlength="6"
              placeholder="000000"
              autocomplete="one-time-code"
              required /></label
          ><small v-if="!firstKeyCreation">{{
            t(
              "Expires in 5 minutes. Maximum 5 attempts.",
              "5 分钟内有效，最多尝试 5 次。",
            )
          }}</small
          ><button
            :class="verification.action === 'project.delete' ? 'btn danger-button full' : 'btn primary full'"
            :disabled="verification.busy || !deletionReady || (!firstKeyCreation && !verification.challengeId)"
          >
            {{
              verification.busy
                ? t("Please wait…", "请稍候…")
                : firstKeyCreation ? t("Create key", "创建密钥") : verification.action === "project.delete" ? t("Verify & delete", "验证并删除") : t("Verify & confirm", "验证并确认")
            }}</button
          ><button
            v-if="!firstKeyCreation"
            class="text-button"
            type="button"
            :disabled="verification.busy || !deletionReady"
            @click="sendVerificationCode"
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
