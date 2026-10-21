import { ref } from "vue";
const allowed = ["en", "zh-CN"];
const param = new URLSearchParams(location.search).get("lang");
if (param && allowed.includes(param))
    localStorage.setItem("metertrail.lang", param);
export const language = ref(localStorage.getItem("metertrail.lang") === "zh-CN" ? "zh-CN" : "en");
document.documentElement.lang = language.value;
export function t(en, zh) {
    return language.value === "zh-CN" ? zh : en;
}
export function setLanguage(value) {
    if (!allowed.includes(value))
        return;
    language.value = value;
    localStorage.setItem("metertrail.lang", value);
    document.documentElement.lang = value;
    if (session.value)
        api("/preferences", { language: value }).catch(() => { });
}
export const session = ref(null);
export class ApiError extends Error {
    code;
    status;
    constructor(code, status) {
        super(code);
        this.code = code;
        this.status = status;
    }
}
export async function api(path, body, method) {
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
    if (!r.ok)
        throw new ApiError(data.code ?? "REQUEST_FAILED", r.status);
    return data;
}
export async function loadSession() {
    try {
        session.value = await api("/me");
    }
    catch {
        session.value = null;
    }
    return session.value;
}
export const n = (v) => v == null ? "—" : new Intl.NumberFormat(language.value).format(Number(v));
export const money = (v) => v == null
    ? t("Unknown", "未知")
    : new Intl.NumberFormat(language.value, {
        style: "currency",
        currency: "USD",
        maximumFractionDigits: 6,
    }).format(Number(v));
export const date = (v) => v ? new Date(v).toISOString().replace("T", " ").slice(0, 19) + " UTC" : "—";
const errors = {
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
export function errorText(e) {
    const code = e instanceof ApiError ? e.code : "NETWORK_ERROR";
    const pair = errors[code];
    return pair ? t(...pair) : t("Request failed: ", "请求失败：") + code;
}
