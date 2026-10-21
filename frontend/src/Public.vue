<script setup lang="ts">
import DocsExamples from "./DocsExamples.vue";
import { computed, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
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
import { api, t, errorText, loadSession, session, ApiError } from "./lib";
const route = useRoute(),
  router = useRouter(),
  page = computed(() => String(route.params.page || "home"));
const docSections = [
  { id: "quickstart", en: "Quickstart", zh: "快速开始" },
  { id: "protocol", en: "Batch protocol", zh: "批次协议" },
  { id: "sdk", en: "SDK packages", zh: "SDK 包" },
  { id: "retries", en: "Errors & retries", zh: "错误与重试" },
  { id: "security", en: "Security", zh: "安全" },
] as const;
const activeDoc = computed(() => {
  const id = route.hash.slice(1);
  return docSections.find((section) => section.id === id)?.id || "quickstart";
});
const docsContent = ref<HTMLElement | null>(null);
watch(activeDoc, () => docsContent.value?.scrollTo({ top: 0 }), {
  flush: "post",
});
const form = reactive({
    email: "",
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
        if (value === "login") router.replace("/app/overview");
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
  busy.value = true;
  error.value = "";
  try {
    if (page.value === "register") {
      if (!form.terms) throw new Error("terms");
      const r = await api("/auth/register", form);
      form.challengeId = r.challengeId;
      router.push("/verify");
    } else if (page.value === "login") {
      await api("/auth/login", form);
      await loadSession();
      router.push("/app/overview");
    } else if (page.value === "verify") {
      await api("/auth/verify", form);
      router.push("/login");
    } else if (page.value === "forgot-password") {
      const r = await api("/auth/challenge", {
        email: form.email,
        action: "reset-password",
      });
      form.challengeId = r.challengeId;
      router.push("/reset-password");
    } else {
      await api("/auth/reset", form);
      router.push("/login");
    }
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function resend() {
  try {
    const r = await api("/auth/challenge", {
      email: form.email,
      action: page.value === "verify" ? "verify-email" : "reset-password",
    });
    form.challengeId = r.challengeId;
    message.value = t(
      "Check your inbox. Codes expire in 5 minutes.",
      "请查收邮件，验证码 5 分钟内有效。",
    );
  } catch (e) {
    error.value = errorText(e);
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
    class="public"
    :class="{
      'docs-page': page === 'docs',
      'home-page': page === 'home',
      'pricing-page': page === 'pricing',
    }"
  >
    <header class="public-header">
      <RouterLink to="/" class="brand"
        ><span class="brand-icon"><Activity :size="20" /></span
        >MeterTrail</RouterLink
      >
      <nav>
        <RouterLink to="/">{{ t("Product", "产品") }}</RouterLink
        ><RouterLink to="/pricing">{{ t("Pricing", "价格") }}</RouterLink
        ><RouterLink to="/docs">{{
          t("Documentation", "开发文档")
        }}</RouterLink>
      </nav>
      <div class="header-actions">
        <RouterLink v-if="session" class="btn primary" to="/app/overview"
          >{{ t("Open console", "进入控制台") }} <ArrowUpRight :size="14"
        /></RouterLink>
        <template v-else-if="sessionChecked"
          ><RouterLink to="/login">{{ t("Sign in", "登录") }}</RouterLink
          ><RouterLink class="btn primary" to="/register"
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
              t("OBSERVABILITY FOR YOUR AI STACK", "为 AI 应用打造的可观测平台")
            }}
          </div>
          <h1>
            {{ t("Every call,", "追踪每次调用，") }}<br /><span>{{
              t("a clearer picture.", "看清每一笔成本。")
            }}</span>
          </h1>
          <p>
            {{
              t(
                "Follow your traces. Understand your tokens. Turn model usage into answers your team can act on.",
                "追踪调用链路，掌握 Token 消耗。将模型使用数据转化为团队可以采取行动的洞察。",
              )
            }}
          </p>
          <div class="hero-buttons">
            <RouterLink
              v-if="sessionChecked"
              :to="session ? '/app/overview' : '/register'"
              class="btn primary large auth-cta"
              >{{
                session
                  ? t("Open console", "进入控制台")
                  : t("Start building for free", "免费开始接入")
              }}<ArrowRight :size="17"
            /></RouterLink>
            <span v-else class="auth-cta-placeholder" aria-hidden="true"></span>
            <RouterLink to="/docs" class="btn large">{{
              t("Read the docs", "阅读接入文档")
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
        </div>
        <div class="hero-console">
          <div class="mock-top">
            <div><i></i><i></i><i></i></div>
            <span>app.metertrail.com</span
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
      <section class="features">
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
              ? t("Open your workspace", "进入工作空间")
              : t("Create your workspace", "创建工作空间")
          }}<ArrowRight :size="18"
        /></RouterLink>
        <span v-else class="auth-cta-placeholder" aria-hidden="true"></span>
      </section>
    </main>
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
          <p>
            {{
              t(
                "Everything you need to understand usage.",
                "掌握应用调用与用量。",
              )
            }}
          </p>
          <ul>
            <li>
              <Check :size="16" />{{ i === 0 ? "50,000" : "100,000" }} units /
              {{ t("month", "月") }}
            </li>
            <li>
              <Check :size="16" />{{
                [
                  t("30 days", "30 天"),
                  t("90 days", "90 天"),
                  t("3 years", "3 年"),
                ][i]
              }}
              {{ t("data access", "数据访问") }}
            </li>
            <li><Check :size="16" /> Trace · Observation · Score</li>
            <li>
              <Check :size="16" />{{
                t("Stop at quota by default", "默认额度用尽后停止接收")
              }}
            </li>
          </ul>
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
    </main>
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
        <div v-if="error" class="notice error" role="alert">{{ error }}</div>
        <div v-if="message" class="notice">{{ message }}</div>
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
            ><button class="text-button" type="button" @click="resend">
              {{ t("Send a new code", "重新发送验证码") }}
            </button></template
          ><label v-if="page === 'register'" class="check-label"
            ><input v-model="form.terms" type="checkbox" required />{{
              t("I agree to the", "我同意")
            }}
            <RouterLink to="/terms">{{
              t("Terms of Service", "服务条款")
            }}</RouterLink></label
          ><button :disabled="busy" class="btn primary full">
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
          </p>
          <RouterLink v-if="page === 'login'" to="/verify">{{
            t("Verify email", "验证邮箱")
          }}</RouterLink></template
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
          <ol>
            <li>
              {{
                t(
                  "Create an account, verify your email, and create a project.",
                  "创建账号、验证邮箱并创建项目。",
                )
              }}
            </li>
            <li>
              {{
                t(
                  "Open Integration & API Keys. Verify the action by email to create a project key.",
                  "进入接入与密钥，通过邮箱验证码创建项目密钥。",
                )
              }}
            </li>
            <li>
              {{
                t(
                  "Set METERTRAIL_API_KEY and METERTRAIL_ENDPOINT to your deployment URL.",
                  "设置 METERTRAIL_API_KEY 和 METERTRAIL_ENDPOINT 为实际部署地址。",
                )
              }}
            </li>
            <li>
              {{
                t(
                  "Run scripts/send-example.mjs, then open the project overview.",
                  "运行 scripts/send-example.mjs，再打开项目概览。",
                )
              }}
            </li>
          </ol>
          <DocsExamples />
        </section>
        <section
          v-else-if="activeDoc === 'protocol'"
          aria-labelledby="protocol"
        >
          <h2 id="protocol">POST /v1/events/batch</h2>
          <p>
            {{
              t(
                'Use schemaVersion "1.0", agent, and events. Each event needs eventId (UUID), type, objectId, revision, startedAt, and data. Trace and Observation also need traceId; Observation needs spanId.',
                '请求包含 schemaVersion "1.0"、agent 和 events。每条事件包含 eventId（UUID）、type、objectId、revision、startedAt、data；Trace 和 Observation 还需 traceId，Observation 需 spanId。',
              )
            }}
          </p>
          <table>
            <tbody>
              <tr>
                <td>{{ t("Batch size", "批次大小") }}</td>
                <td>1–500 events · 1 MiB · 8 KiB / event</td>
              </tr>
              <tr>
                <td>{{ t("Time window", "时间窗口") }}</td>
                <td>
                  {{
                    t(
                      "Last 7 days; 5 minutes future tolerance",
                      "最近 7 天；允许未来 5 分钟偏差",
                    )
                  }}
                </td>
              </tr>
              <tr>
                <td>202 Accepted</td>
                <td>
                  {{
                    t(
                      "Durably stored. Poll GET /v1/batches/{batchId} for final results.",
                      "已可靠写入，轮询 GET /v1/batches/{batchId} 获取最终结果。",
                    )
                  }}
                </td>
              </tr>
              <tr>
                <td>usage</td>
                <td>
                  {{
                    t(
                      "Nonnegative integers or null; cached input is part of total input.",
                      "非负整数或 null；缓存输入是总输入的子集。",
                    )
                  }}
                </td>
              </tr>
            </tbody>
          </table>
        </section>
        <section v-else-if="activeDoc === 'sdk'" aria-labelledby="sdk">
          <h2 id="sdk">{{ t("SDK packages", "SDK 包") }}</h2>
          <p>
            {{
              t(
                "Node.js / TypeScript, Python, and Java source SDKs are available in the dedicated GitHub repository. Follow its installation instructions; packages are not yet published to npm, PyPI, or Maven Central.",
                "Node.js / TypeScript、Python、Java 源码 SDK 已放到独立 GitHub 仓库，请按仓库说明安装；尚未发布到 npm、PyPI 或 Maven Central。",
              )
            }}
          </p>
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
        <section v-else-if="activeDoc === 'retries'" aria-labelledby="retries">
          <h2 id="retries">
            {{ t("Retries preserve identity", "重试必须保留身份") }}
          </h2>
          <p>
            {{
              t(
                "Keep eventId, objectId, revision, and content unchanged. Back off with jitter on network errors and 5xx. Respect Retry-After on RATE_LIMITED. Pause on QUOTA_EXCEEDED or invalid keys. Split on 413. Inspect per-event errors after 202.",
                "重试保留 eventId、objectId、revision 和内容。网络错误或 5xx 指数退避并加抖动；RATE_LIMITED 遵循 Retry-After；额度不足或密钥无效暂停；413 拆批；202 后检查逐条结果。",
              )
            }}
          </p>
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
        ><RouterLink to="/refunds">{{ t("Refunds", "退款") }}</RouterLink
        ><a
          href="https://github.com/zhiyuan213/llm-usage-monitor"
          target="_blank"
          rel="noreferrer"
          >GitHub ↗</a
        >
      </nav>
    </footer>
  </div>
</template>
