<script setup lang="ts">
import PerformanceReference from "./PerformanceReference.vue";
import PublicDemo from './PublicDemo.vue';
import Tutorials from './Tutorials.vue';
import { tutorials } from './tutorial-content';
import { publicPage, trackAction } from './analytics';
import Checkout from "./Checkout.vue";
import PlanFeatures from "./PlanFeatures.vue";
import ErrorReference from "./ErrorReference.vue";
import ProtocolReference from "./ProtocolReference.vue";
import ProtocolFields from "./ProtocolFields.vue";
import BatchQueryReference from "./BatchQueryReference.vue";
import BatchQueryFields from "./BatchQueryFields.vue";
import { computed, reactive, ref, watch, onMounted, onBeforeUnmount } from "vue";
import { verificationCooldown } from "./verification-cooldown";
import { useRoute, useRouter } from "vue-router";
import {
  Menu,
  X,
  Activity,
  ArrowRight,
  GitBranch,
  Layers,
  Coins,
  Code2,
  Check,
  ShieldCheck,
  ArrowUpRight,
} from "lucide-vue-next";
import { api, t, errorText, loadSession, session, ApiError, language } from "./lib";
const route = useRoute(),
  router = useRouter(),
  page = computed(() => route.meta.tutorial ? 'tutorials' : String(route.params.page || "home"));
watch([() => route.path, language], () => {
  const guide = tutorials.find(item => route.path === `/tutorials/${item.slug}`);
  const info = publicPage(route.path);
  document.title = guide ? `${t(guide.en, guide.zh)} | MeterTrail` : info?.page_title || 'MeterTrail';
  const description = document.querySelector<HTMLMetaElement>('meta[name="description"]');
  if (description && info) description.content = guide ? t(guide.summary[0], guide.summary[1]) : route.path === '/' ? t('Track AI requests, token usage and estimated model costs. Explore a demo or start with a runnable integration tutorial.', '查看 AI 请求、Token 用量与模型估算费用。无需登录体验演示，或通过可运行教程开始接入。') : info.page_title;
  let canonical = document.querySelector<HTMLLinkElement>('link[rel="canonical"]');
  if (info) {
    if (!canonical) { canonical = document.createElement('link'); canonical.rel = 'canonical'; document.head.appendChild(canonical); }
    canonical.href = info.page_location;
  } else canonical?.remove();
}, { immediate: true });
const docSections = [
  { id: "quickstart", en: "Quickstart", zh: "快速开始" },
  { id: "protocol", en: "REST integration", zh: "Rest接入" },
  { id: "sdk", en: "SDK integration", zh: "SDK接入" },
  { id: "fields", en: "Ingestion fields", zh: "接入字段" },
  { id: "batch-status", en: "Query status API", zh: "查询状态接口" },
  { id: "batch-fields", en: "Query status fields", zh: "查询状态字段" },
  { id: "retries", en: "Errors & retries", zh: "错误与重试" },
  { id: "performance", en: "Performance & capacity", zh: "性能与容量" },
  { id: "security", en: "Security", zh: "安全" },
] as const;
const activeDoc = computed(() => {
  const id = route.hash.slice(1);
  return docSections.find((section) => section.id === id)?.id || "quickstart";
});
const menuOpen=ref(false);
watch(()=>route.fullPath,()=>menuOpen.value=false);
const docsContent = ref<HTMLElement | null>(null);
watch(activeDoc, () => docsContent.value?.scrollTo({ top: 0 }), {
  flush: "post",
});
const form = reactive({
    email: ["forgot-password", "reset-password"].includes(page.value) ? session.value?.email || "" : "",
    password: "",
    code: "",
    challengeId: "",
    terms: false,
    rememberMe: true,
  }),
  busy = ref(false),
  error = ref(""),
  message = ref(""),
  copied = ref(false);
const resendBusy = ref(false);
const cooldown = verificationCooldown(sessionStorage);
const clock = ref(Date.now());
const resendSeconds = computed(() => cooldown.remaining(clock.value));
let cooldownTimer: ReturnType<typeof setInterval>;
onMounted(() => { cooldownTimer = setInterval(() => { clock.value = Date.now(); }, 1000); });
onBeforeUnmount(() => clearInterval(cooldownTimer));
function codeSent() { cooldown.start(); clock.value = Date.now(); }
const sessionChecked = ref(!!session.value);
watch(page, () => {
  error.value = "";
  message.value = "";
});
watch(
  page,
  async (value, _previous, onCleanup) => {
    let active = true;
    onCleanup(() => {
      active = false;
    });
    try {
      const user = await api("/me");
      if (active && !busy.value) {
        session.value = user;
        if (["forgot-password", "reset-password"].includes(value) && !form.email) form.email = user.email;
        if (value === "login") router.replace("/app/workspace");
      }
    } catch (e) {
      if (active && !busy.value && e instanceof ApiError && e.status === 401)
        session.value = null;
    } finally {
      if (active) sessionChecked.value = true;
    }
  },
  { immediate: true },
);
const authPage = computed(() =>
  ["login", "register", "verify", "forgot-password", "reset-password"].includes(
    page.value,
  ),
);
async function submit() {
  if (busy.value || resendBusy.value) return;
  if (page.value === "forgot-password" && resendSeconds.value > 0) {
    message.value = t("A code was already sent. Please wait before requesting another.", "验证码已发送，请稍候再重新获取。");
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    if (page.value === "register") {
      if (!form.terms) throw new Error("terms");
      const r = await api("/auth/register", form);
      form.challengeId = r.challengeId;
      codeSent();
      router.push("/verify");
    } else if (page.value === "login") {
      await api("/auth/login", form);
      await loadSession();
      router.push("/app/workspace");
    } else if (page.value === "verify") {
      await api("/auth/verify", form);
      router.push("/login");
    } else if (page.value === "forgot-password") {
      const r = await api("/auth/challenge", {
        email: form.email,
        action: "reset-password",
      });
      form.challengeId = r.challengeId;
      form.code = "";
      codeSent();
      router.push("/reset-password");
    } else {
      await api("/auth/reset", form);
      router.push("/login");
    }
  } catch (e) {
    if (page.value === "forgot-password" && e instanceof ApiError && e.status === 429) codeSent();
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function resend() {
  if (busy.value || resendBusy.value || resendSeconds.value > 0) return;
  resendBusy.value = true;
  error.value = "";
  try {
    const r = await api("/auth/challenge", {
      email: form.email,
      action: page.value === "verify" ? "verify-email" : "reset-password",
    });
    form.challengeId = r.challengeId;
    form.code = "";
    codeSent();
    message.value = t(
      "Check your inbox. Codes expire in 5 minutes.",
      "请查收邮件，验证码 5 分钟内有效。",
    );
  } catch (e) {
    if (e instanceof ApiError && e.status === 429) codeSent();
    error.value = errorText(e);
  } finally {
    resendBusy.value = false;
  }
}
const example = `curl "$METERTRAIL_ENDPOINT/v1/events/batch" \\\n+  -H "Authorization: Bearer $METERTRAIL_API_KEY" \\\n+  -H 'Content-Type: application/json' \\\n+  --data @examples/batch.json`;
async function copy() {
  await navigator.clipboard.writeText(example);
  copied.value = true;
  setTimeout(() => (copied.value = false), 2000);
}
</script>
<template>
  <div
    class="public public-v2"
    :class="{
      'docs-page': page === 'docs',
      'home-page': page === 'home',
      'pricing-page': page === 'pricing',
      'checkout-public': page === 'pay',
    }"
  >
    <header class="public-header">
      <RouterLink to="/" class="brand"
        ><span class="brand-icon"><Activity :size="20" /></span
        >MeterTrail</RouterLink
      >
      <button type="button" class="public-menu-toggle" :aria-expanded="menuOpen" aria-controls="public-navigation" :aria-label="t('Toggle navigation','展开或收起导航')" @click="menuOpen=!menuOpen"><component :is="menuOpen?X:Menu" :size="22" /></button>
      <nav id="public-navigation" :class="{'menu-open':menuOpen}" @keydown.esc="menuOpen=false">
        <RouterLink to="/#product">{{ t("Product", "产品") }}</RouterLink
        ><RouterLink to="/pricing">{{ t("Pricing", "价格") }}</RouterLink
        ><RouterLink to="/docs">{{
          t("Documentation", "开发文档")
        }}</RouterLink>
        <RouterLink to="/demo">{{ t('Demo', '在线演示') }}</RouterLink>
        <RouterLink to="/tutorials">{{ t('Tutorials', '接入教程') }}</RouterLink>
      </nav>
      <div class="header-actions">
        <RouterLink v-if="session" class="btn primary" to="/app/workspace"
          >{{ t("Open console", "进入控制台") }} <ArrowUpRight :size="14"
        /></RouterLink>
        <template v-else-if="sessionChecked"
          ><RouterLink to="/login">{{ t("Sign in", "登录") }}</RouterLink
          ><RouterLink class="btn primary" to="/register" @click="trackAction('start_free',route.path)"
            >{{ t("Start free", "免费开始") }}
            <ArrowUpRight :size="14" /></RouterLink
        ></template>
      </div>
    </header>
    <main v-if="page === 'home'">
      <section class="hero">
        <div class="hero-copy">
          <div class="eyebrow">
            <span class="live-dot"></span>
            {{
              t("FOR DEVELOPERS BUILDING AI APPLICATIONS", "为 AI 应用开发者打造")
            }}
          </div>
          <h1>
            {{ t("Track AI calls.", "看清 AI 调用，") }}<br /><span>{{
              t("Understand the cost.", "掌握用量与成本。")
            }}</span>
          </h1>
          <p>
            {{
              t(
                "See which requests are slow, where tokens are spent, and how model costs add up. Send data from your backend with Node.js, Python, Java or REST.",
                "查看哪些请求慢、Token 花在哪里，以及模型费用如何产生。通过 Node.js、Python、Java 或 REST 从服务端接入。",
              )
            }}
          </p>
          <div class="hero-buttons">
            <RouterLink
              v-if="sessionChecked"
              :to="session ? '/app/workspace' : '/register'"
              class="btn primary large auth-cta"
              @click="!session && trackAction('start_free', '/')"
              >{{
                session
                  ? t("Open console", "进入控制台")
                  : t("Start free", "免费开始")
              }}<ArrowRight :size="17"
            /></RouterLink>
            <span v-else class="auth-cta-placeholder" aria-hidden="true"></span>
            <RouterLink to="/demo" class="btn large" @click="trackAction('open_demo','/')">{{
              t("Explore the demo", "无需登录，查看演示")
            }}</RouterLink>
          </div>
          <small
            ><Check :size="14" />
            {{
              t(
                "50,000 units / month. No credit card required.",
                "每月 50,000 units，无需信用卡。",
              )
            }}</small
          >
          <RouterLink class="unit-help" to="/pricing#units">{{t("What counts as a unit?", "什么是 unit？")}}</RouterLink>
          <p class="integration-proof">{{t('Your application calls the model directly. Send metadata through REST or a supported SDK; cost estimates require reported usage and matching model rates.','应用直接调用模型，通过 REST 或支持的 SDK 上报元数据；费用估算需要用量数据及匹配的模型价格。')}}</p>
        </div>
        <div class="hero-console">
          <div class="mock-top">
            <div><i></i><i></i><i></i></div>
            <span>metertrail.com</span
            ><span class="badge">{{
              t("Illustrative preview", "界面示意")
            }}</span>
          </div>
          <div class="mock-body">
            <div class="mock-title">
              <Activity :size="20" /><strong>resume-api</strong
              ><span class="badge success">production</span>
            </div>
            <div class="mock-metrics">
              <div>
                <small>{{ t("Model calls", "模型调用") }}</small
                ><strong>28,640</strong><span class="green">↗ 12.8%</span>
              </div>
              <div>
                <small>{{ t("Total tokens", "总 Token") }}</small
                ><strong>12.8M</strong
                ><span>{{ t("Across 3 models", "来自 3 个模型") }}</span>
              </div>
              <div>
                <small>{{ t("Model cost", "模型成本") }}</small
                ><strong>$42.68</strong><span>USD</span>
              </div>
            </div>
            <div class="mock-chart">
              <div class="chart-label">
                {{ t("Your usage, in focus", "用量趋势，一目了然")
                }}<span>{{ t("Last 7 days", "最近 7 天") }}</span>
              </div>
              <div class="bars">
                <div
                  v-for="(v, i) in [
                    32, 47, 41, 62, 53, 76, 68, 91, 78, 87, 72, 97,
                  ]"
                  :key="i"
                  :style="{ height: v + '%' }"
                >
                  <span></span>
                </div>
              </div>
              <div class="chart-axis">
                <span>Mon</span><span>Tue</span><span>Wed</span><span>Thu</span
                ><span>Fri</span><span>Sat</span><span>Sun</span>
              </div>
            </div>
            <div class="mock-trace">
              <span class="live-dot"></span><span>resume.generate</span
              ><span>1,900 tokens</span><span class="badge success">1.85s</span>
            </div>
          </div>
          <div class="floating-card">
            <ShieldCheck :size="23" />
            <div>
              <strong>{{
                t("Your requests stay yours", "业务请求由你掌控")
              }}</strong
              ><small>{{
                t("Asynchronous. No model proxy.", "异步采集，无需模型代理。")
              }}</small>
            </div>
          </div>
        </div>
      </section>
      <section id="product" class="features">
        <article
          v-for="(item, i) in [
            [
              t('Follow the whole trace', '查看完整链路'),
              t(
                'Connect generations, tools, and retrievals. Find where a request slows down or fails.',
                '串联生成、工具和检索步骤，定位延迟与错误。',
              ),
            ],
            [
              t('Make costs make sense', '理解真实成本'),
              t(
                'Track input, output, and cached tokens with versioned model pricing.',
                '按版本化模型价格核算输入、输出与缓存用量。',
              ),
            ],
            [
              t('Understand your customers', '了解客户消耗'),
              t(
                'Explore usage by customer, user, and feature, without collecting prompt content.',
                '按客户、用户与功能分析消耗，默认不采集提示词正文。',
              ),
            ],
            [
              t('Built for your workflow', '融入开发流程'),
              t(
                'Send data with Python, Node.js, Java, or the REST API.',
                '使用 Python、Node.js、Java 或 REST API 上报数据。',
              ),
            ],
          ]"
          :key="i"
        >
          <component :is="[GitBranch, Coins, Layers, Code2][i]" :size="23" />
          <h3>{{ item[0] }}</h3>
          <p>{{ item[1] }}</p>
        </article>
      </section>
      <section class="home-learning">
        <div class="page-intro"><span class="eyebrow">{{ t('YOUR FIRST REQUEST, STEP BY STEP', '从第一条请求开始') }}</span><h2>{{ t('Choose your integration guide', '选择适合你的接入教程') }}</h2><p>{{ t('Runnable examples, expected results and troubleshooting. No prompt content required.', '提供可运行示例、预期结果与排错步骤，无需上报提示词正文。') }}</p></div>
        <div class="tutorial-grid"><article v-for="item in tutorials" :key="item.slug" class="panel"><h3>{{ t(item.en,item.zh) }}</h3><p>{{ t(item.summary[0],item.summary[1]) }}</p><RouterLink :to="`/tutorials/${item.slug}`" @click="trackAction('open_tutorial','/')">{{ t('View tutorial →', '查看教程 →') }}</RouterLink></article></div>
      </section>
      <section class="home-learning">
        <div class="page-intro"><span class="eyebrow">{{ t('ILLUSTRATIVE WORKFLOW', '费用排查示例 · 非真实客户案例') }}</span><h2>{{ t('From a cost spike to a concrete next step', '费用上涨之后，知道从哪里查起') }}</h2><p>{{ t('Use reported usage and configured prices to investigate. Savings depend on your application; they are not guaranteed.', '根据已上报用量和配置价格排查问题，优化效果取决于你的应用，不承诺节省比例。') }}</p></div>
        <div class="tutorial-grid">
          <article class="panel"><h3>{{ t('1. Find the costly feature', '1. 找到费用上涨的功能') }}</h3><p>{{ t('Group estimated costs by feature to see whether document analysis is driving the increase.', '按功能查看估算费用，发现文档分析的费用上涨，再结合调用量判断是流量增长还是单次消耗增加。') }}</p></article>
          <article class="panel"><h3>{{ t('2. Inspect the requests', '2. 定位具体请求') }}</h3><p>{{ t('Inspect the trace and model calls. Check token usage, model selection and repeated steps to form a hypothesis.', '查看调用链及模型调用，核对 Token 用量、模型选择和重复步骤，判断是否与输入过长或重复调用有关。') }}</p></article>
          <article class="panel"><h3>{{ t('3. Compare after a change', '3. 验证修改后的表现') }}</h3><p>{{ t('Mark the release, then compare estimated cost, failures and latency before and after. Account for changes in traffic and pricing.', '记录发布标记，对比修改前后的估算费用、失败与耗时，并结合流量及模型价格变化判断效果。') }}</p></article>
        </div>
        <div class="home-demo-actions"><RouterLink to="/demo" class="btn" @click="trackAction('open_demo','/')">{{ t('Explore the interactive demo', '查看交互演示') }}<ArrowRight :size="16" /></RouterLink></div>
      </section>
      <section class="home-learning panel home-plan-summary">
        <div class="home-plan-copy"><h2>{{ t('Choose the plan for your history and controls', '按历史数据需求与提醒方式选择套餐') }}</h2>
        <p>{{ t('Core includes cost diagnosis, release comparisons and weekly reports with 90-day rolling retention. Pro adds 3-year rolling retention and custom budget warning and slow-call thresholds. Both include 100,000 units per month; Pro does not add ingestion quota.', 'Core 提供费用诊断、发布对比和自动周报，滚动保留90天数据。Pro 适合需要长期追溯历史、自定义预算提醒线与慢调用阈值的用户，滚动保留3年数据。两者每月均含100,000 units，Pro 不增加接入额度。') }}</p>
        </div><div class="home-plan-actions"><RouterLink class="btn primary" to="/pricing">{{ t('Compare plans', '比较套餐') }}<ArrowRight :size="16" /></RouterLink>
        <RouterLink class="plan-reference" to="/docs#performance">{{ t('Integration limits & test conditions →', '接入限制与测试条件 →') }}</RouterLink></div>
      </section>
      <section class="bottom-cta">
        <div>
          <span class="eyebrow">{{
            t("LESS GUESSWORK. MORE CLARITY.", "减少猜测，清晰洞察。")
          }}</span>
          <h2>
            {{
              t(
                "Your next insight starts with one trace.",
                "从一条 Trace 开始洞察。",
              )
            }}
          </h2>
        </div>
        <RouterLink
          v-if="sessionChecked"
          class="btn primary large auth-cta"
          :to="session ? '/app/workspace' : '/register'"
          >{{
            session
              ? t("Manage projects", "进入项目管理")
              : t("Create your workspace", "创建工作空间")
          }}<ArrowRight :size="18"
        /></RouterLink>
        <span v-else class="auth-cta-placeholder" aria-hidden="true"></span>
      </section>
    </main>
    <PublicDemo v-else-if="page === 'demo'" />
    <Tutorials v-else-if="page === 'tutorials'" />
    <main v-else-if="page === 'pricing'" class="public-content">
      <div class="page-intro">
        <div class="eyebrow">
          {{ t("SIMPLE, OBJECT-BASED PRICING", "按对象计量，清晰定价") }}
        </div>
        <h1>
          {{ t("Start small. See the whole picture.", "轻松起步，洞察全局。") }}
        </h1>
        <p>
          {{
            t(
              "One new Trace, Observation, or Score = one unit. Updates and retries cost no additional units.",
              "每个新 Trace、Observation 或 Score 计 1 unit，更新与重试不重复收费。",
            )
          }}
        </p>
      </div>
      <div class="plans">
        <article
          v-for="(plan, i) in ['Free', 'Core', 'Pro']"
          :key="plan"
          :class="['panel', { featured: i === 1 }]"
        >
          <span v-if="i === 1" class="plan-tag">{{
            t("FOR GROWING TEAMS", "适合成长团队")
          }}</span>
          <h3>{{ plan }}</h3>
          <div class="price">
            ${{ [0, 29, 199][i] }}<small>/ {{ t("month", "月") }}</small>
          </div>
          <PlanFeatures :index="i" />
          <RouterLink
            :to="session ? '/app/billing' : '/register'"
            :class="['btn', i === 1 ? 'primary' : '']"
            >{{ t("Get started", "开始使用") }}<ArrowRight :size="16"
          /></RouterLink>
        </article>
      </div>
      <section class="panel pricing-details">
        <h2>{{ t("Usage that stays in your control", "由你控制用量") }}</h2>
        <p>
          {{
            t(
              "Core and Pro can opt into metered overage with an email-confirmed monthly cap. Free must upgrade or wait for a new month.",
              "Core 和 Pro 可经邮箱验证开启超额计费并设置月度上限。Free 需升级或等待下个账期。",
            )
          }}
        </p>
        <table>
          <thead>
            <tr>
              <th>{{ t("Monthly units", "当月累计 units") }}</th>
              <th>
                {{ t("Price per 100,000 units", "每 100,000 units 价格") }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in [
                ['100,001 – 1,000,000', '$8'],
                ['1,000,001 – 10,000,000', '$7'],
                ['10,000,001 – 50,000,000', '$6.50'],
                ['50,000,001 +', '$6'],
              ]"
            >
              <td>{{ row[0] }}</td>
              <td>{{ row[1] }}</td>
            </tr>
          </tbody>
        </table>
        <p class="muted">
          {{
            t(
              "Progressive rates, prorated to actual units. Core at 500,000 units = $29 + $32 = $61 before tax. Model provider costs are separate. Add-on packs are not yet available.",
              "分段累进，按实际 units 比例计费。Core 使用 500,000 units 时为 $29 + $32 = $61（税前）。模型供应商费用单独核算。加量包暂未开放。",
            )
          }}
        </p>
      </section>
      <section id="units" class="panel units-guide"><h2>{{t('How units work','units 如何计算')}}</h2><p>{{t('Each new Trace, Observation or Score uses one unit. These are example structures, not fixed limits on your requests. Model-provider charges are separate.','每新建一个 Trace、Observation 或 Score 使用 1 unit。下表是示例结构，不是每次请求的固定消耗；模型供应商费用另计。')}}</p><div class="table-wrap"><table><thead><tr><th>{{t('Example','示例')}}</th><th>{{t('Objects per request','每次新建对象')}}</th><th>{{t('Units per request','每次 units')}}</th><th>{{t('Requests within 50,000 units','50,000 units 可记录次数')}}</th></tr></thead><tbody><tr><td>{{t('One model call','一次模型调用')}}</td><td>1 Trace + 1 Observation</td><td>2</td><td>25,000</td></tr><tr><td>{{t('With a score','附加评分')}}</td><td>1 Trace + 1 Observation + 1 Score</td><td>3</td><td>16,666</td></tr><tr><td>{{t('Multiple steps and a score','多步骤及评分')}}</td><td>1 Trace + 2 Observations + 1 Score</td><td>4</td><td>12,500</td></tr></tbody></table></div><p>{{t('Updates and exact retries do not add units. Extra steps create extra objects. Units are not tokens.','更新和原样重试不增加 units；更多步骤会产生更多对象。units 不是 Token 数。')}}</p></section>
    </main>
    <main v-else-if="page === 'pay'" class="public-content checkout-page"><Checkout /></main>
    <main v-else-if="authPage" class="auth-layout">
      <div class="auth-aside">
        <Activity :size="40" />
        <h1>
          {{ t("Clarity for every", "洞察每一次") }}<br />{{
            t("AI interaction.", "AI 交互。")
          }}
        </h1>
        <p>
          {{
            t(
              "Trace performance, understand usage, and build with confidence.",
              "追踪性能、理解用量，更有把握地构建应用。",
            )
          }}
        </p>
        <div class="auth-line"></div>
        <small>TRACE · MEASURE · UNDERSTAND</small>
      </div>
      <section class="auth-card">
        <div class="eyebrow">METERTRAIL</div>
        <h2>
          {{
            (
              {
                login: t("Welcome back", "欢迎回来"),
                register: t("Create your account", "创建账号"),
                verify: t("Verify your email", "验证邮箱"),
                "forgot-password": t("Reset your password", "找回密码"),
                "reset-password": t("Choose a new password", "设置新密码"),
              } as any
            )[page]
          }}
        </h2>
        <p>
          {{ t("Your workspace, one step closer.", "即将进入你的工作空间。") }}
        </p>
        <div v-if="route.query.oauth === 'cancelled'" class="notice" role="status">
          {{ t("GitHub authorization was cancelled. You can try again or sign in with email.", "已取消 GitHub 授权，可以重试或使用邮箱登录。") }}
        </div>
        <div v-if="route.query.oauth === 'failed'" class="notice error" role="alert">
          {{ t("GitHub authorization failed. Please try again.", "GitHub 授权失败，请重试。") }}
        </div>
        <div v-if="error" class="notice error" role="alert">{{ error }}</div>
        <div v-if="message" class="notice">{{ message }}</div>
        <p v-if="page === 'forgot-password' && resendSeconds > 0" class="notice" role="status">
          {{ t(`A code was already sent. Try again in ${resendSeconds}s.`, `验证码已发送，请在${resendSeconds}秒后重试。`) }}
        </p>
        <form @submit.prevent="submit">
          <label
            >{{ t("Email address", "邮箱地址")
            }}<input
              v-model="form.email"
              type="email"
              required
              autocomplete="email"
              placeholder="you@company.com" /></label
          ><label v-if="['login', 'register', 'reset-password'].includes(page)"
            >{{ t("Password", "密码")
            }}<input
              v-model="form.password"
              type="password"
              required
              :minlength="page === 'login' ? 1 : 12"
              maxlength="72"
              :autocomplete="
                page === 'login' ? 'current-password' : 'new-password'
              "
              :placeholder="
                t('At least 12 characters', '至少 12 个字符')
              " /></label
          ><label v-if="page === 'login'" class="check-label remember-login">
            <input v-model="form.rememberMe" type="checkbox" />
            {{ t("Keep me signed in for 30 days", "保持登录 30 天") }} </label
          ><template v-if="['verify', 'reset-password'].includes(page)"
            ><label
              >{{ t("Challenge ID", "验证请求 ID")
              }}<input v-model="form.challengeId" required /></label
            ><label
              >{{ t("6-digit email code", "6 位邮箱验证码")
              }}<input
                v-model="form.code"
                inputmode="numeric"
                pattern="[0-9]{6}"
                maxlength="6"
                required
                autocomplete="one-time-code" /></label
            ><button class="text-button" type="button" :disabled="busy || resendBusy || resendSeconds > 0" @click="resend">
              {{ resendBusy ? t("Sending…", "正在发送…") : resendSeconds > 0 ? t(`Resend in ${resendSeconds}s`, `${resendSeconds}秒后可重新发送`) : t("Send a new code", "重新发送验证码") }}
            </button></template
          ><label v-if="page === 'register'" class="check-label"
            ><input v-model="form.terms" type="checkbox" required />{{
              t("I agree to the", "我同意")
            }}
            <RouterLink to="/terms">{{
              t("Terms of Service", "服务条款")
            }}</RouterLink></label
          ><button :disabled="busy || resendBusy || (page === 'forgot-password' && resendSeconds > 0)" class="btn primary full">
            {{
              busy
                ? t("Please wait…", "请稍候…")
                : page === "login"
                  ? t("Sign in", "登录")
                  : page === "register"
                    ? t("Create account", "创建账号")
                    : t("Continue", "继续")
            }}<ArrowRight :size="16" />
          </button>
        </form>
        <template v-if="page === 'login' || page === 'register'"
          ><div class="divider">{{ t("or", "或") }}</div>
          <a
            :href="
              '/api/auth/github?rememberMe=' +
              (page === 'login' && form.rememberMe)
            "
            class="btn full"
            ><GitBranch :size="17" />{{
              t("Continue with GitHub", "通过 GitHub 继续")
            }}</a
          >
          <p class="auth-links">
            <RouterLink :to="page === 'login' ? '/register' : '/login'">{{
              page === "login"
                ? t("Create an account", "创建账号")
                : t("Already have an account? Sign in", "已有账号？登录")
            }}</RouterLink
            ><RouterLink to="/forgot-password">{{
              t("Forgot password?", "忘记密码？")
            }}</RouterLink>
            <RouterLink v-if="page === 'login'" to="/verify">{{
              t("Verify email", "验证邮箱")
            }}</RouterLink>
          </p></template
        >
      </section>
    </main>
    <main v-else-if="page === 'docs'" class="docs-layout">
      <aside :aria-label="t('Documentation sections', '文档目录')">
        <span class="eyebrow">{{ t("DEVELOPER DOCS", "开发者文档") }}</span
        ><RouterLink
          v-for="section in docSections"
          :key="section.id"
          :to="{ path: '/docs', hash: '#' + section.id }"
          :class="{ active: activeDoc === section.id }"
          :aria-current="activeDoc === section.id ? 'page' : undefined"
          >{{ t(section.en, section.zh) }}</RouterLink
        >
      </aside>
      <article
        ref="docsContent"
        class="docs-content"
        :aria-label="t('Documentation content', '文档内容')"
        tabindex="0"
      >
        <section v-if="activeDoc === 'quickstart'" aria-labelledby="quickstart">
          <h1 id="quickstart">
            {{
              t("Your first trace, in minutes.", "几分钟内接入第一条 Trace。")
            }}
          </h1>
          <p>
            {{
              t(
                "MeterTrail collects observability events asynchronously. Your application calls the model provider directly.",
                "MeterTrail 异步采集可观测事件，应用直接调用模型供应商。",
              )
            }}
          </p>
          <ol class="quickstart-steps">
            <li>
              <strong>{{ t("Create a project", "创建项目") }}</strong>
              {{
                t(
                  "Create an account, verify your email, and create a project.",
                  "创建账号、验证邮箱并创建项目。",
                )
              }}
            </li>
            <li>
              <strong>{{ t("Create an API key", "创建密钥") }}</strong>
              {{
                t(
                  "Open API key management. Verify the action by email to create a project key.",
                  "进入密钥管理，通过邮箱验证码创建项目密钥。",
                )
              }}
            </li>
            <li>
              <strong>{{ t("Copy a runnable example", "复制可运行示例") }}</strong>
              {{
                t(
                  "Choose Node.js, Python or REST below. The guide includes environment setup and a complete script; no repository clone is needed.",
                  "选择下方 Node.js、Python 或 REST 教程，按说明设置环境并复制完整脚本，无需克隆仓库。",
                )
              }}
            </li>
            <li>
              <strong>{{ t("Send an example", "发送示例") }}</strong>
              {{
                t(
                  "Send the sample, check the returned batchId, then open Traces in the same project.",
                  "发送示例，查询返回的 batchId，再进入同一项目的调用记录查看。",
                )
              }}
            </li>
          </ol>
          <div class="tutorial-grid"><RouterLink v-for="guide in tutorials" :key="guide.slug" class="panel" :to="'/tutorials/'+guide.slug"><h2>{{t(guide.en,guide.zh)}}</h2><p>{{t(guide.summary[0],guide.summary[1])}}</p>{{t('Open runnable guide →','查看可运行教程 →')}}</RouterLink></div>
          <p>{{t('A successful sample sends 1 Trace and 1 model observation (2 units). The provider and model are examples; configure matching rates to estimate cost.','完整示例发送 1 条 Trace 和 1 条模型 Observation（2 units）。供应商和模型为示例值，估算费用需配置匹配价格。')}}</p>
        </section>
        <section
          v-else-if="activeDoc === 'protocol'"
          aria-labelledby="protocol"
        >
          <h2 id="protocol">{{ t("REST integration", "Rest接入") }}</h2>
          <ProtocolReference />
        </section>
        <section v-else-if="activeDoc === 'sdk'" aria-labelledby="sdk">
          <h2 id="sdk">{{ t("SDK integration", "SDK接入") }}</h2>
          <p>
            {{
              t(
                "Node.js / TypeScript, Python, and Java source SDKs are available in the dedicated GitHub repository. Follow its installation instructions; packages are not yet published to npm, PyPI, or Maven Central.",
                "Node.js / TypeScript、Python、Java 源码 SDK 已放到独立 GitHub 仓库，请按仓库说明安装；尚未发布到 npm、PyPI 或 Maven Central。",
              )
            }}
          </p>
          <p>{{ t("SDK 0.2.0 ends sending after the HTTP 200 receipt (status is always pending). Query live results only when needed: Node getBatch(batchId), Python get_batch(batch_id), Java getBatch(batchId). All use the same project key.", "SDK 0.2.0 收到 HTTP 200 回执即结束上报，status 固定 pending。按需查询实时结果：Node getBatch(batchId)、Python get_batch(batch_id)、Java getBatch(batchId)，均使用同一项目密钥。") }}</p>
          <div
            class="hero-buttons"
            style="flex-wrap: wrap; margin-bottom: 24px"
          >
            <a
              class="btn primary"
              href="https://github.com/zhiyuan213/llm-usage-monitor-sdk"
              target="_blank"
              rel="noopener noreferrer"
              >{{ t("SDK repository", "SDK GitHub 仓库") }}
              <ArrowUpRight :size="15"
            /></a>
            <a
              class="btn"
              href="https://github.com/zhiyuan213/llm-usage-monitor-sdk/tree/main/node"
              target="_blank"
              rel="noopener noreferrer"
              >Node.js / TypeScript</a
            >
            <a
              class="btn"
              href="https://github.com/zhiyuan213/llm-usage-monitor-sdk/tree/main/python"
              target="_blank"
              rel="noopener noreferrer"
              >Python</a
            >
            <a
              class="btn"
              href="https://github.com/zhiyuan213/llm-usage-monitor-sdk/tree/main/java"
              target="_blank"
              rel="noopener noreferrer"
              >Java</a
            >
          </div>
        </section>
        <section v-else-if="activeDoc === 'fields'" aria-labelledby="fields">
          <h2 id="fields">{{ t("Ingestion fields", "接入字段") }}</h2>
          <ProtocolFields />
        </section>
        <section v-else-if="activeDoc === 'batch-status'" aria-labelledby="batch-status">
          <h2 id="batch-status">{{ t("Query status API", "查询状态接口") }}</h2>
          <BatchQueryReference />
        </section>
        <section v-else-if="activeDoc === 'batch-fields'" aria-labelledby="batch-fields">
          <h2 id="batch-fields">{{ t("Query status fields", "查询状态字段") }}</h2>
          <BatchQueryFields />
        </section>
        <section v-else-if="activeDoc === 'retries'" aria-labelledby="retries">
          <h2 id="retries">
            {{ t("Retries preserve identity", "重试必须保留身份") }}
          </h2>
          <p>
            {{
              t(
                "Keep eventId, objectId, revision, and content unchanged. Back off with jitter on network errors and 5xx. Respect Retry-After on RATE_LIMITED. Pause on QUOTA_EXCEEDED or invalid keys. Split on 413. Inspect per-event errors after 200.",
                "重试保留 eventId、objectId、revision 和内容。网络错误或 5xx 指数退避并加抖动；RATE_LIMITED 遵循 Retry-After；额度不足或密钥无效暂停；413 拆批；200 后检查逐条结果。",
              )
            }}
          </p>
          <ErrorReference />
        </section>
        <section v-else-if="activeDoc === 'performance'" aria-label="Performance & capacity">
          <PerformanceReference />
        </section>
        <section v-else aria-labelledby="security">
          <h2 id="security">
            {{ t("Collect only what you need", "仅采集必要数据") }}
          </h2>
          <p>
            {{
              t(
                "Never send API keys, passwords, complete prompts, or model responses in event data. Unknown usage and missing model prices remain unknown, never zero.",
                "不要在事件中发送 API Key、密码、完整提示词或模型回答。未知用量与缺失价格显示为未知，不会按零处理。",
              )
            }}
          </p>
        </section>
      </article>
    </main>
    <main v-else-if="page==='security'" class="legal public-content"><span class="eyebrow">METERTRAIL</span><h1>{{t('Security and data handling','安全与数据处理')}}</h1><h2>{{t('Send metadata, not secrets','上报元数据，不上报秘密')}}</h2><p>{{t('Report request timing, status, model names and token counts. Do not include model API keys, passwords, full prompts or response text in events. Run integrations on your backend.','上报请求耗时、状态、模型名称与 Token 数，不要在事件中包含模型 API 密钥、密码、完整提示词或回答正文。接入代码应运行在服务端。')}}</p><h2>{{t('Project access and account protection','项目访问与账号保护')}}</h2><p>{{t('Project API keys scope ingestion to their project and can be revoked. Sensitive account and key operations require email verification. Console changes use session-bound CSRF protection; sessions use HttpOnly cookies.','项目 API 密钥限定所属项目，可撤销。敏感账号及密钥操作需要邮箱验证；控制台变更使用会话绑定的 CSRF 保护，会话使用 HttpOnly Cookie。')}}</p><h2>{{t('Retention and deletion','数据保留与删除')}}</h2><p>{{t('Queryable detail follows the active plan retention window. Project deletion revokes its keys and removes analytical detail asynchronously. This page describes implemented controls, not an independent security certification or an availability guarantee.','可查询明细遵循当前套餐保留范围。删除项目会撤销密钥并异步移除分析明细。本页描述已实现的措施，不代表独立安全认证或可用性保证。')}}</p><p><RouterLink to="/privacy">{{t('Read the privacy policy','查看隐私政策')}}</RouterLink></p><p>{{t('Report a security concern:','报告安全问题：')}} <a href="mailto:13428961230@163.com">13428961230@163.com</a></p></main>
    <main v-else class="legal public-content">
      <span class="eyebrow">METERTRAIL</span>
      <h1>
        {{
          page === "privacy"
            ? t("Privacy policy", "隐私政策")
            : page === "refunds"
              ? t("Refund policy", "退款政策")
              : t("Terms of service", "服务条款")
        }}
      </h1>
      <div class="notice">
        {{
          t(
            "Deployment policy template — the operator must complete legal entity, contact, jurisdiction, and payment details before public launch.",
            "部署政策模板：运营方需在公开上线前补充主体、联系方式、司法管辖与支付条款。",
          )
        }}
      </div>
      <h2>{{ t("Data and service", "数据与服务") }}</h2>
      <p>
        {{
          t(
            "MeterTrail stores account information and the trace metadata you choose to submit. It does not require complete prompts or responses. Access to usage records follows your plan retention period.",
            "MeterTrail 保存账号信息和你主动上报的链路元数据，不要求完整提示词或回答，用量明细按套餐保留期限提供访问。",
          )
        }}
      </p>
      <template v-if="page === 'privacy'">
        <h2>{{ t('Optional website statistics', '可选的网站访问统计') }}</h2>
        <p>{{ t('If you allow statistics, public pages load Google Analytics (GA4) to measure page visits and selected actions such as opening the demo or copying an example. Google may process browser/device information and your IP address in providing this service. Analytics cookies distinguish visits. We do not send email addresses, account IDs, API keys, message content, URL queries or hashes. We do not load the analytics tag on account, console or payment pages. Advertising features are disabled.', '你允许统计后，公开页面才会加载 Google Analytics（GA4），用于统计页面访问、打开演示或复制示例等操作。Google 在提供服务时可能处理浏览器、设备信息及 IP 地址，并通过统计 Cookie 区分访问。我们不会发送邮箱、账号 ID、API 密钥、消息内容、网址查询参数或片段；账号、控制台与支付页面不加载统计代码，广告功能关闭。') }}</p>
        <p>{{ t('Declining does not affect the service. Use Statistics preferences on a public page to change your choice or withdraw permission. Measurement covers consenting visitors and can be blocked by the browser; it is not a complete count of all users.', '拒绝不影响正常使用。你可以随时通过公开页面的“统计偏好”修改选择或撤回许可。统计仅覆盖同意的访客，也可能被浏览器拦截，不代表全部用户数量。') }} <a href="https://policies.google.com/privacy" rel="noopener noreferrer">{{ t('Google privacy policy', 'Google 隐私政策') }}</a></p>
      </template>
      <h2>{{ t("Billing and cancellation", "计费与取消") }}</h2>
      <p>
        {{
          t(
            "Monitoring units and model provider charges are separate. New objects stop at the quota unless you explicitly authorize overage. Refund, cancellation, tax, and dispute terms must be confirmed by the operator and the selected payment provider before accepting live payments.",
            "监控 units 与模型供应商费用单独核算。默认额度用尽停止接收新增对象，超额需明确授权。退款、取消、税费与争议规则需由运营方及支付平台确认后才能开启正式收款。",
          )
        }}
      </p>
      <h2>{{ t("Account and deletion", "账号与删除") }}</h2>
      <p>
        {{
          t(
            "Sensitive changes require email verification. Project deletion removes analytics detail asynchronously; minimal billing and audit records may remain under the applicable retention policy.",
            "敏感变更需要邮箱验证。删除项目会异步删除分析明细；必要的账单和审计信息按适用保留政策留存。",
          )
        }}
      </p>
    </main>
    <footer>
      <RouterLink class="brand" to="/"
        ><Activity :size="20" />MeterTrail</RouterLink
      ><span>{{
        t("Visibility for what you build.", "看清你构建的每一步。")
      }}</span>
      <nav>
        <RouterLink to="/privacy">{{ t("Privacy", "隐私") }}</RouterLink
        ><RouterLink to="/terms">{{ t("Terms", "条款") }}</RouterLink
        ><RouterLink to="/refunds">{{ t("Refunds", "退款") }}</RouterLink>
        <a href="https://basaltkit.com/" target="_blank" rel="noopener noreferrer">{{ t('Developer tools', '开发工具') }}</a>
        <RouterLink to="/security">{{t('Security','安全')}}</RouterLink>
      </nav>
    </footer>
  </div>
</template>
