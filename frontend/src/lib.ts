import { ref } from "vue";
const allowed = ["en", "zh-CN"];
const param = new URLSearchParams(location.search).get("lang");
if (param && allowed.includes(param))
  localStorage.setItem("metertrail.lang", param);
export const language = ref(
  localStorage.getItem("metertrail.lang") === "zh-CN" ? "zh-CN" : "en",
);
document.documentElement.lang = language.value;
export function t(en: string, zh: string) {
  return language.value === "zh-CN" ? zh : en;
}
export function setLanguage(value: string) {
  if (!allowed.includes(value)) return;
  language.value = value;
  localStorage.setItem("metertrail.lang", value);
  document.documentElement.lang = value;
  if (session.value) api("/preferences", { language: value }).catch(() => {});
}
export const session = ref<any>(null);
export class ApiError extends Error {
  constructor(
    public code: string,
    public status: number,
  ) {
    super(code);
  }
}
export async function api(path: string, body?: unknown, method?: string) {
  const r = await fetch("/api" + path, {
    method: method ?? (body === undefined ? "GET" : "POST"),
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      ...(session.value?.csrf ? { "X-CSRF-Token": session.value.csrf } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await r.json().catch(() => ({ code: "SERVICE_UNAVAILABLE" }));
  if (!r.ok) throw new ApiError(data.code ?? "REQUEST_FAILED", r.status);
  return data;
}
export async function loadSession() {
  try {
    session.value = await api("/me");
  } catch {
    session.value = null;
  }
  return session.value;
}
export const n = (v: any) =>
  v == null ? "—" : new Intl.NumberFormat(language.value).format(Number(v));
export const money = (v: any) =>
  v == null
    ? t("Unknown", "未知")
    : new Intl.NumberFormat(language.value, {
        style: "currency",
        currency: "USD",
        maximumFractionDigits: 6,
      }).format(Number(v));
export const date = (v: string) =>
  v ? new Date(v).toISOString().replace("T", " ").slice(0, 19) + " UTC" : "—";
const errors: Record<string, [string, string]> = {
  REFUND_CONFIRMATION_REQUIRED: ["Enter the transaction ID to confirm.", "请输入完整交易编号确认。"],
  REFUND_REASON_REQUIRED: ["Enter a refund reason.", "请填写退款原因。"],
  REFUND_WINDOW_EXPIRED: ["The payment is outside the seven-day refund window. Review it in the provider dashboard.", "此付款不在7天退款期限内，请到支付平台人工核查。"],
  REFUND_PROVIDER_UNSUPPORTED: ["Process this payment in its provider dashboard.", "请到此订单对应的支付平台处理。"],
  REFUND_PAYMENT_MISMATCH: ["The provider payment does not match this account. No refund was submitted.", "支付平台付款与账号不匹配，未提交退款。"],
  PAYMENT_NOT_REFUNDABLE: ["This payment is unpaid, already refunded, or has a refund in progress.", "此订单未付款、已退款或已有退款处理中。"],
  REFUND_NEWER_PAYMENT_EXISTS: ["A newer payment exists for this subscription. Review it in Creem.", "该订阅已有更新的付款，请到 Creem 核查。"],
  REFUND_QUOTE_CHANGED: ["Payment details changed. Close this dialog and review again.", "付款信息已变化，请关闭弹窗并重新核对。"],
  REFUND_PROVIDER_UNAVAILABLE: ["Could not verify the provider result. Reconcile status before retrying.", "无法核实支付平台结果，请先核对状态。"],
  REFUND_NOT_FOUND: ["No refund request was found.", "未找到退款申请。"],
  PAYMENT_NOT_FOUND: ["Payment not found.", "未找到付款记录。"],
    WEEKLY_NOT_ENABLED: ['Enable weekly reports before sending.', '请先开启周报，再点击发送。'],
    INVALID_BUDGET_SETTINGS: ['Enter a positive USD budget (up to 1 billion, at most 6 decimal places) and a warning from 50% to 95%.', '请输入有效的美元预算（大于零、最多 10 亿、最多 6 位小数），提醒线为 50%～95%。'],
    PRO_CUSTOM_RULES_REQUIRED: ['Custom warning and slow-call thresholds require Pro.', '自定义提醒线或慢调用阈值需要 Pro 套餐。'],
    INVALID_RELIABILITY_FILTER: ['Choose a supported period, scope and slow-call threshold.', '请选择支持的统计区间、分析对象及慢调用阈值。'],
    INSIGHTS_PLAN_REQUIRED: ['These analysis tools require Core or Pro.', '使用这些分析工具需要 Core 或 Pro 套餐。'],
  INVALID_INSIGHT_WINDOW: ['Choose a supported analysis period.', '请选择支持的统计区间。'],
  INVALID_RELEASE_TIME: ['Choose a past release time within your plan retention.', '请选择套餐数据保留范围内、早于当前时间的发布时间。'],
  RELEASE_NOT_FOUND: ['This release marker no longer exists.', '这条发布标记已不存在。'],
  REPORT_NOT_FOUND: ['This report no longer exists or is outside your retention period.', '这份周报已不存在，或已超出数据保留范围。'],
  INVALID_TIMEZONE: ['Enter a valid timezone, such as Asia/Shanghai or UTC.', '请输入有效时区，例如 Asia/Shanghai 或 UTC。'],
  INVALID_WEEKLY_SETTINGS: ['Choose whether weekly reports should be enabled.', '请选择是否开启周报。'],
  ADMIN_REQUIRED: ["Administrator access is required.", "仅管理员可以访问。"],
  MESSAGE_NOT_FOUND: ["This conversation was not found.", "消息会话不存在或无权访问。"],
  INVALID_TITLE: ["Enter a subject, or shorten it if it is too long.", "请填写标题；如果标题太长，请精简后再发送。"],
  INVALID_BODY: ["Enter a message, or shorten it or send it in parts if it is too long.", "请填写消息内容；如果内容太长，请精简或分条发送。"],
  MESSAGE_THREAD_LIMIT: ["The conversation limit has been reached. Add to an existing conversation.", "已达到会话数量上限，请在现有会话中补充。"],
  MESSAGE_REPLY_LIMIT: ["This conversation has reached 200 replies. Start a new one.", "此会话已达到 200 条回复，请新建会话。"],
  MESSAGE_RETRY_CONFLICT: ["This submission ID was used for different content. Refresh before sending again.", "此提交标识已用于其他内容，请刷新后重新发送。"],
  USAGE_COLLECTION_NOT_CONFIGURED: ["Additional billing is not enabled yet.", "尚未启用加购及超额扣款。"],
  ADDON_UNAVAILABLE: ["An active paid subscription with at least 15 minutes left is required.", "需要活跃的付费订阅，且距离账期结束至少15分钟。"],
  CHARGE_PENDING_REVIEW: ["The payment result is being reconciled. Do not purchase again.", "付款结果正在核对，请勿重复购买。"],
  SUBSCRIPTION_REQUIRED: ["Choose a paid plan first.", "请先订阅付费套餐。"],
  SUBSCRIPTION_CHANGE_UNAVAILABLE: ["The subscription cannot be changed now. Check its status or scheduled cancellation in Manage subscription.", "当前无法切换，请在管理订阅核对订阅状态或计划取消。"],
  PLAN_ALREADY_SELECTED: ["This is already your current plan. Refresh Billing.", "当前已是此套餐，请刷新账单。"],
  PLAN_PREVIEW_UNAVAILABLE: ["Could not obtain a billing preview. No change was submitted.", "无法获取账单预览，未提交套餐变更。"],
  PLAN_QUOTE_NOT_FOUND: ["The preview no longer exists. Choose the plan again.", "预览不存在，请重新选择套餐。"],
  PLAN_QUOTE_EXPIRED: ["The preview expired or was already submitted. Refresh and preview again.", "预览已过期或已提交，请刷新并重新预览。"],
  PLAN_QUOTE_CHANGED: ["The subscription or amount changed. Review a new preview before confirming.", "订阅或金额已变化，请重新预览并确认。"],
  PLAN_CHANGE_PENDING: ["A plan change is pending confirmation. Do not submit again; check Manage subscription or contact support.", "已有套餐变更等待确认，请勿重复提交，请在管理订阅核对或联系支持。"],
  PLAN_CHANGE_REJECTED: ["Paddle rejected the change. Check the payment method and API permissions, then obtain a new preview.", "Paddle 未接受变更，请检查付款方式及服务端权限后重新预览。"],
  KEY_MUST_BE_REVOKED: [
    "Revoke this key before deleting it.",
    "请先撤销密钥，再删除。",
  ],
  KEY_NOT_FOUND: ["This key no longer exists.", "这条密钥已不存在。"],
  ALERT_NOT_FOUND: [
    "This alert rule no longer exists.",
    "这条告警规则已不存在。",
  ],
  UNAUTHENTICATED: ["Please sign in.", "请先登录。"],
  INVALID_CREDENTIALS: ["Incorrect email or password.", "邮箱或密码错误。"],
  EMAIL_NOT_VERIFIED: [
    "Verify your email before signing in.",
    "请先验证邮箱。",
  ],
  INVALID_VERIFICATION: [
    "The code is invalid, expired, or already used. Request a new code.",
    "验证码无效、过期或已使用，请重新获取。",
  ],
  RATE_LIMITED: [
    "Too many requests. Please wait one minute.",
    "请求过于频繁，请等待一分钟。",
  ],
  PAYMENTS_NOT_CONFIGURED: [
    "Payments are not configured yet. Your plan has not changed.",
    "尚未配置支付服务，套餐未发生变化。",
  ],
  GITHUB_NOT_CONFIGURED: [
    "GitHub login is not configured yet.",
    "尚未配置 GitHub 登录。",
  ],
  PASSWORD_TOO_SHORT: [
    "Use a password with at least 12 characters.",
    "密码至少需要 12 个字符。",
  ],
  EMAIL_UNAVAILABLE: [
    "This email cannot be registered. Try signing in or resetting your password.",
    "此邮箱无法注册，请尝试登录或找回密码。",
  ],
  CONFLICT: [
    "This record already exists or conflicts with another record.",
    "记录已存在或与现有记录冲突。",
  ],
  USE_BILLING_PORTAL: [
    "An active subscription already exists. Contact support to change it.",
    "已有关联订阅，请联系支持变更。",
  ],
};
export function errorText(e: unknown) {
  const code = e instanceof ApiError ? e.code : "NETWORK_ERROR";
  const pair = errors[code];
  return pair ? t(...pair) : t("Request failed: ", "请求失败：") + code;
}
