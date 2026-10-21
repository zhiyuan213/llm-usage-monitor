import DocsExamples from "./DocsExamples.vue";
import { computed, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Activity, ArrowRight, GitBranch, Layers, Coins, Code2, Check, ShieldCheck, ArrowUpRight, } from "lucide-vue-next";
import { api, t, errorText, loadSession } from "./lib";
const route = useRoute(), router = useRouter(), page = computed(() => String(route.params.page || "home"));
const form = reactive({
    email: "",
    password: "",
    code: "",
    challengeId: "",
    terms: false,
}), busy = ref(false), error = ref(""), message = ref(""), copied = ref(false);
watch(page, () => {
    error.value = "";
    message.value = "";
});
const authPage = computed(() => ["login", "register", "verify", "forgot-password", "reset-password"].includes(page.value));
async function submit() {
    busy.value = true;
    error.value = "";
    try {
        if (page.value === "register") {
            if (!form.terms)
                throw new Error("terms");
            const r = await api("/auth/register", form);
            form.challengeId = r.challengeId;
            router.push("/verify");
        }
        else if (page.value === "login") {
            await api("/auth/login", form);
            await loadSession();
            router.push("/app/overview");
        }
        else if (page.value === "verify") {
            await api("/auth/verify", form);
            router.push("/login");
        }
        else if (page.value === "forgot-password") {
            const r = await api("/auth/challenge", {
                email: form.email,
                action: "reset-password",
            });
            form.challengeId = r.challengeId;
            router.push("/reset-password");
        }
        else {
            await api("/auth/reset", form);
            router.push("/login");
        }
    }
    catch (e) {
        error.value = errorText(e);
    }
    finally {
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
        message.value = t("Check your inbox. Codes expire in 5 minutes.", "请查收邮件，验证码 5 分钟内有效。");
    }
    catch (e) {
        error.value = errorText(e);
    }
}
const example = `curl "$METERTRAIL_ENDPOINT/v1/events/batch" \\\n+  -H "Authorization: Bearer $METERTRAIL_API_KEY" \\\n+  -H 'Content-Type: application/json' \\\n+  --data @examples/batch.json`;
async function copy() {
    await navigator.clipboard.writeText(example);
    copied.value = true;
    setTimeout(() => (copied.value = false), 2000);
}
const __VLS_ctx = {};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
void __VLS_ctx, __VLS_components, __VLS_intrinsics, __VLS_directives;
// @ts-ignore
__VLS_withDotValue(page, {});
// @ts-ignore
__VLS_withDotValue(authPage, {});
// @ts-ignore
__VLS_withDotValue(error, {});
// @ts-ignore
__VLS_withDotValue(message, {});
// @ts-ignore
__VLS_withDotValue(form, {});
// @ts-ignore
__VLS_withDotValue(busy, {});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "public" },
});
/** @type {__VLS_StyleScopedClasses['public']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.header, __VLS_intrinsics.header)({
    ...{ class: "public-header" },
});
/** @type {__VLS_StyleScopedClasses['public-header']} */ ;
let __VLS_0;
/** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
RouterLink;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
    // @ts-ignore
    to: "/", ...{ class: "brand" },
}));
const __VLS_2 = __VLS_1({
    to: "/",
    ...{ class: "brand" },
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
/** @type {__VLS_StyleScopedClasses['brand']} */ ;
const { default: __VLS_5 } = __VLS_nonNull(__VLS_3.slots);
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "brand-icon" },
});
/** @type {__VLS_StyleScopedClasses['brand-icon']} */ ;
let __VLS_6;
/** @ts-ignore @type { | typeof __VLS_components.Activity} */
Activity;
// @ts-ignore
const __VLS_7 = __VLS_asFunctionalComponent1(__VLS_6, new __VLS_6({
    // @ts-ignore
    size: (20),
}));
const __VLS_8 = __VLS_7({
    size: (20),
}, ...__VLS_functionalComponentArgsRest(__VLS_7));
var __VLS_3;
__VLS_asFunctionalElement1(__VLS_intrinsics.nav, __VLS_intrinsics.nav)({});
let __VLS_11;
/** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
RouterLink;
// @ts-ignore
const __VLS_12 = __VLS_asFunctionalComponent1(__VLS_11, new __VLS_11({
    // @ts-ignore
    to: "/",
}));
const __VLS_13 = __VLS_12({
    to: "/",
}, ...__VLS_functionalComponentArgsRest(__VLS_12));
const { default: __VLS_16 } = __VLS_nonNull(__VLS_14.slots);
(__VLS_unwrap(t, {})("Product", "产品"));
// @ts-ignore
[t,];
var __VLS_14;
let __VLS_17;
/** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
RouterLink;
// @ts-ignore
const __VLS_18 = __VLS_asFunctionalComponent1(__VLS_17, new __VLS_17({
    // @ts-ignore
    to: "/pricing",
}));
const __VLS_19 = __VLS_18({
    to: "/pricing",
}, ...__VLS_functionalComponentArgsRest(__VLS_18));
const { default: __VLS_22 } = __VLS_nonNull(__VLS_20.slots);
(__VLS_unwrap(t, {})("Pricing", "价格"));
// @ts-ignore
[t,];
var __VLS_20;
let __VLS_23;
/** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
RouterLink;
// @ts-ignore
const __VLS_24 = __VLS_asFunctionalComponent1(__VLS_23, new __VLS_23({
    // @ts-ignore
    to: "/docs",
}));
const __VLS_25 = __VLS_24({
    to: "/docs",
}, ...__VLS_functionalComponentArgsRest(__VLS_24));
const { default: __VLS_28 } = __VLS_nonNull(__VLS_26.slots);
(__VLS_unwrap(t, {})("Documentation", "开发文档"));
// @ts-ignore
[t,];
var __VLS_26;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "header-actions" },
});
/** @type {__VLS_StyleScopedClasses['header-actions']} */ ;
let __VLS_29;
/** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
RouterLink;
// @ts-ignore
const __VLS_30 = __VLS_asFunctionalComponent1(__VLS_29, new __VLS_29({
    // @ts-ignore
    to: "/login",
}));
const __VLS_31 = __VLS_30({
    to: "/login",
}, ...__VLS_functionalComponentArgsRest(__VLS_30));
const { default: __VLS_34 } = __VLS_nonNull(__VLS_32.slots);
(__VLS_unwrap(t, {})("Sign in", "登录"));
// @ts-ignore
[t,];
var __VLS_32;
let __VLS_35;
/** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
RouterLink;
// @ts-ignore
const __VLS_36 = __VLS_asFunctionalComponent1(__VLS_35, new __VLS_35({
    // @ts-ignore
    ...{ class: "btn primary" }, to: "/register",
}));
const __VLS_37 = __VLS_36({
    ...{ class: "btn primary" },
    to: "/register",
}, ...__VLS_functionalComponentArgsRest(__VLS_36));
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['primary']} */ ;
const { default: __VLS_40 } = __VLS_nonNull(__VLS_38.slots);
(__VLS_unwrap(t, {})("Start free", "免费开始"));
let __VLS_41;
/** @ts-ignore @type { | typeof __VLS_components.ArrowUpRight} */
ArrowUpRight;
// @ts-ignore
const __VLS_42 = __VLS_asFunctionalComponent1(__VLS_41, new __VLS_41({
    // @ts-ignore
    size: (14),
}));
const __VLS_43 = __VLS_42({
    size: (14),
}, ...__VLS_functionalComponentArgsRest(__VLS_42));
// @ts-ignore
[t,];
var __VLS_38;
if (page.value === 'home') {
    __VLS_asFunctionalElement1(__VLS_intrinsics.main, __VLS_intrinsics.main)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
        ...{ class: "hero" },
    });
    /** @type {__VLS_StyleScopedClasses['hero']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "hero-copy" },
    });
    /** @type {__VLS_StyleScopedClasses['hero-copy']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "eyebrow" },
    });
    /** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "live-dot" },
    });
    /** @type {__VLS_StyleScopedClasses['live-dot']} */ ;
    (__VLS_unwrap(t, {})("OBSERVABILITY FOR YOUR AI STACK", "为 AI 应用打造的可观测平台"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.h1, __VLS_intrinsics.h1)({});
    (__VLS_unwrap(t, {})("Every call,", "追踪每次调用，"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.br)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    (__VLS_unwrap(t, {})("a clearer picture.", "看清每一笔成本。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_unwrap(t, {})("Follow your traces. Understand your tokens. Turn model usage into answers your team can act on.", "追踪调用链路，掌握 Token 消耗。将模型使用数据转化为团队可以采取行动的洞察。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "hero-buttons" },
    });
    /** @type {__VLS_StyleScopedClasses['hero-buttons']} */ ;
    let __VLS_46;
    /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
    RouterLink;
    // @ts-ignore
    const __VLS_47 = __VLS_asFunctionalComponent1(__VLS_46, new __VLS_46({
        // @ts-ignore
        to: "/register", ...{ class: "btn primary large" },
    }));
    const __VLS_48 = __VLS_47({
        to: "/register",
        ...{ class: "btn primary large" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_47));
    /** @type {__VLS_StyleScopedClasses['btn']} */ ;
    /** @type {__VLS_StyleScopedClasses['primary']} */ ;
    /** @type {__VLS_StyleScopedClasses['large']} */ ;
    const { default: __VLS_51 } = __VLS_nonNull(__VLS_49.slots);
    (__VLS_unwrap(t, {})("Start building for free", "免费开始接入"));
    let __VLS_52;
    /** @ts-ignore @type { | typeof __VLS_components.ArrowRight} */
    ArrowRight;
    // @ts-ignore
    const __VLS_53 = __VLS_asFunctionalComponent1(__VLS_52, new __VLS_52({
        // @ts-ignore
        size: (17),
    }));
    const __VLS_54 = __VLS_53({
        size: (17),
    }, ...__VLS_functionalComponentArgsRest(__VLS_53));
    // @ts-ignore
    [t, t, t, t, t, page,];
    var __VLS_49;
    let __VLS_57;
    /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
    RouterLink;
    // @ts-ignore
    const __VLS_58 = __VLS_asFunctionalComponent1(__VLS_57, new __VLS_57({
        // @ts-ignore
        to: "/docs", ...{ class: "btn large" },
    }));
    const __VLS_59 = __VLS_58({
        to: "/docs",
        ...{ class: "btn large" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_58));
    /** @type {__VLS_StyleScopedClasses['btn']} */ ;
    /** @type {__VLS_StyleScopedClasses['large']} */ ;
    const { default: __VLS_62 } = __VLS_nonNull(__VLS_60.slots);
    (__VLS_unwrap(t, {})("Read the docs", "阅读接入文档"));
    // @ts-ignore
    [t,];
    var __VLS_60;
    __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
    let __VLS_63;
    /** @ts-ignore @type { | typeof __VLS_components.Check} */
    Check;
    // @ts-ignore
    const __VLS_64 = __VLS_asFunctionalComponent1(__VLS_63, new __VLS_63({
        // @ts-ignore
        size: (14),
    }));
    const __VLS_65 = __VLS_64({
        size: (14),
    }, ...__VLS_functionalComponentArgsRest(__VLS_64));
    (__VLS_unwrap(t, {})("50,000 units / month. No credit card required.", "每月 50,000 units，无需信用卡。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "hero-console" },
    });
    /** @type {__VLS_StyleScopedClasses['hero-console']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "mock-top" },
    });
    /** @type {__VLS_StyleScopedClasses['mock-top']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.i, __VLS_intrinsics.i)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.i, __VLS_intrinsics.i)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.i, __VLS_intrinsics.i)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "badge" },
    });
    /** @type {__VLS_StyleScopedClasses['badge']} */ ;
    (__VLS_unwrap(t, {})("Illustrative preview", "界面示意"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "mock-body" },
    });
    /** @type {__VLS_StyleScopedClasses['mock-body']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "mock-title" },
    });
    /** @type {__VLS_StyleScopedClasses['mock-title']} */ ;
    let __VLS_68;
    /** @ts-ignore @type { | typeof __VLS_components.Activity} */
    Activity;
    // @ts-ignore
    const __VLS_69 = __VLS_asFunctionalComponent1(__VLS_68, new __VLS_68({
        // @ts-ignore
        size: (20),
    }));
    const __VLS_70 = __VLS_69({
        size: (20),
    }, ...__VLS_functionalComponentArgsRest(__VLS_69));
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "badge success" },
    });
    /** @type {__VLS_StyleScopedClasses['badge']} */ ;
    /** @type {__VLS_StyleScopedClasses['success']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "mock-metrics" },
    });
    /** @type {__VLS_StyleScopedClasses['mock-metrics']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
    (__VLS_unwrap(t, {})("Model calls", "模型调用"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "green" },
    });
    /** @type {__VLS_StyleScopedClasses['green']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
    (__VLS_unwrap(t, {})("Total tokens", "总 Token"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    (__VLS_unwrap(t, {})("Across 3 models", "来自 3 个模型"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
    (__VLS_unwrap(t, {})("Model cost", "模型成本"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "mock-chart" },
    });
    /** @type {__VLS_StyleScopedClasses['mock-chart']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "chart-label" },
    });
    /** @type {__VLS_StyleScopedClasses['chart-label']} */ ;
    (__VLS_unwrap(t, {})("Your usage, in focus", "用量趋势，一目了然"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    (__VLS_unwrap(t, {})("Last 7 days", "最近 7 天"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "bars" },
    });
    /** @type {__VLS_StyleScopedClasses['bars']} */ ;
    const __VLS_73 = __VLS_tryAsConstant(([
        32, 47, 41, 62, 53, 76, 68, 91, 78, 87, 72, 97,
    ]));
    for (const [v, i] of __VLS_vFor(__VLS_nonNull(__VLS_73))) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            key: (i),
            ...{ style: ({ height: v + '%' }) },
        });
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
        // @ts-ignore
        [t, t, t, t, t, t, t, t,];
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "chart-axis" },
    });
    /** @type {__VLS_StyleScopedClasses['chart-axis']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "mock-trace" },
    });
    /** @type {__VLS_StyleScopedClasses['mock-trace']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "live-dot" },
    });
    /** @type {__VLS_StyleScopedClasses['live-dot']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "badge success" },
    });
    /** @type {__VLS_StyleScopedClasses['badge']} */ ;
    /** @type {__VLS_StyleScopedClasses['success']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "floating-card" },
    });
    /** @type {__VLS_StyleScopedClasses['floating-card']} */ ;
    let __VLS_74;
    /** @ts-ignore @type { | typeof __VLS_components.ShieldCheck} */
    ShieldCheck;
    // @ts-ignore
    const __VLS_75 = __VLS_asFunctionalComponent1(__VLS_74, new __VLS_74({
        // @ts-ignore
        size: (23),
    }));
    const __VLS_76 = __VLS_75({
        size: (23),
    }, ...__VLS_functionalComponentArgsRest(__VLS_75));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
    (__VLS_unwrap(t, {})("Your requests stay yours", "业务请求由你掌控"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
    (__VLS_unwrap(t, {})("Asynchronous. No model proxy.", "异步采集，无需模型代理。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
        ...{ class: "features" },
    });
    /** @type {__VLS_StyleScopedClasses['features']} */ ;
    const __VLS_79 = __VLS_tryAsConstant(([
        [
            __VLS_unwrap(t, {})('Follow the whole trace', '查看完整链路'),
            __VLS_unwrap(t, {})('Connect generations, tools, and retrievals. Find where a request slows down or fails.', '串联生成、工具和检索步骤，定位延迟与错误。'),
        ],
        [
            __VLS_unwrap(t, {})('Make costs make sense', '理解真实成本'),
            __VLS_unwrap(t, {})('Track input, output, and cached tokens with versioned model pricing.', '按版本化模型价格核算输入、输出与缓存用量。'),
        ],
        [
            __VLS_unwrap(t, {})('Understand your customers', '了解客户消耗'),
            __VLS_unwrap(t, {})('Explore usage by customer, user, and feature, without collecting prompt content.', '按客户、用户与功能分析消耗，默认不采集提示词正文。'),
        ],
        [
            __VLS_unwrap(t, {})('Built for your workflow', '融入开发流程'),
            __VLS_unwrap(t, {})('Send data with Python, Node.js, Java, or the REST API.', '使用 Python、Node.js、Java 或 REST API 上报数据。'),
        ],
    ]));
    for (const [item, i] of __VLS_vFor(__VLS_nonNull(__VLS_79))) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.article, __VLS_intrinsics.article)({
            key: (i),
        });
        const __VLS_80 = ([__VLS_unwrap(GitBranch, {}), __VLS_unwrap(Coins, {}), __VLS_unwrap(Layers, {}), __VLS_unwrap(Code2, {})][i]);
        // @ts-ignore
        const __VLS_81 = __VLS_asFunctionalComponent1(__VLS_80, new __VLS_80({
            // @ts-ignore
            size: (23),
        }));
        const __VLS_82 = __VLS_81({
            size: (23),
        }, ...__VLS_functionalComponentArgsRest(__VLS_81));
        __VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
        (item[0]);
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
        (item[1]);
        // @ts-ignore
        [t, t, t, t, t, t, t, t, t, t, GitBranch, Coins, Layers, Code2,];
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
        ...{ class: "bottom-cta" },
    });
    /** @type {__VLS_StyleScopedClasses['bottom-cta']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "eyebrow" },
    });
    /** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
    (__VLS_unwrap(t, {})("LESS GUESSWORK. MORE CLARITY.", "减少猜测，清晰洞察。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
    (__VLS_unwrap(t, {})("Your next insight starts with one trace.", "从一条 Trace 开始洞察。"));
    let __VLS_85;
    /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
    RouterLink;
    // @ts-ignore
    const __VLS_86 = __VLS_asFunctionalComponent1(__VLS_85, new __VLS_85({
        // @ts-ignore
        ...{ class: "btn primary large" }, to: "/register",
    }));
    const __VLS_87 = __VLS_86({
        ...{ class: "btn primary large" },
        to: "/register",
    }, ...__VLS_functionalComponentArgsRest(__VLS_86));
    /** @type {__VLS_StyleScopedClasses['btn']} */ ;
    /** @type {__VLS_StyleScopedClasses['primary']} */ ;
    /** @type {__VLS_StyleScopedClasses['large']} */ ;
    const { default: __VLS_90 } = __VLS_nonNull(__VLS_88.slots);
    (__VLS_unwrap(t, {})("Create your workspace", "创建工作空间"));
    let __VLS_91;
    /** @ts-ignore @type { | typeof __VLS_components.ArrowRight} */
    ArrowRight;
    // @ts-ignore
    const __VLS_92 = __VLS_asFunctionalComponent1(__VLS_91, new __VLS_91({
        // @ts-ignore
        size: (18),
    }));
    const __VLS_93 = __VLS_92({
        size: (18),
    }, ...__VLS_functionalComponentArgsRest(__VLS_92));
    // @ts-ignore
    [t, t, t,];
    var __VLS_88;
}
else if (page.value === 'pricing') {
    __VLS_asFunctionalElement1(__VLS_intrinsics.main, __VLS_intrinsics.main)({
        ...{ class: "public-content" },
    });
    /** @type {__VLS_StyleScopedClasses['public-content']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "page-intro" },
    });
    /** @type {__VLS_StyleScopedClasses['page-intro']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "eyebrow" },
    });
    /** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
    (__VLS_unwrap(t, {})("SIMPLE, OBJECT-BASED PRICING", "按对象计量，清晰定价"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.h1, __VLS_intrinsics.h1)({});
    (__VLS_unwrap(t, {})("Start small. See the whole picture.", "轻松起步，洞察全局。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_unwrap(t, {})("One new Trace, Observation, or Score = one unit. Updates and retries cost no additional units.", "每个新 Trace、Observation 或 Score 计 1 unit，更新与重试不重复收费。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "plans" },
    });
    /** @type {__VLS_StyleScopedClasses['plans']} */ ;
    const __VLS_96 = __VLS_tryAsConstant((['Free', 'Core', 'Pro']));
    for (const [plan, i] of __VLS_vFor(__VLS_nonNull(__VLS_96))) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.article, __VLS_intrinsics.article)({
            key: (plan),
            ...{ class: (['panel', { featured: i === 1 }]) },
        });
        /** @type {__VLS_StyleScopedClasses['featured']} */ ;
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        if (i === 1) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                ...{ class: "plan-tag" },
            });
            /** @type {__VLS_StyleScopedClasses['plan-tag']} */ ;
            (__VLS_unwrap(t, {})("FOR GROWING TEAMS", "适合成长团队"));
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
        (plan);
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "price" },
        });
        /** @type {__VLS_StyleScopedClasses['price']} */ ;
        ([0, 29, 199][i]);
        __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
        (__VLS_unwrap(t, {})("month", "月"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
        (__VLS_unwrap(t, {})("Everything you need to understand usage.", "掌握应用调用与用量。"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.ul, __VLS_intrinsics.ul)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
        let __VLS_97;
        /** @ts-ignore @type { | typeof __VLS_components.Check} */
        Check;
        // @ts-ignore
        const __VLS_98 = __VLS_asFunctionalComponent1(__VLS_97, new __VLS_97({
            // @ts-ignore
            size: (16),
        }));
        const __VLS_99 = __VLS_98({
            size: (16),
        }, ...__VLS_functionalComponentArgsRest(__VLS_98));
        (i === 0 ? "50,000" : "100,000");
        (__VLS_unwrap(t, {})("month", "月"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
        let __VLS_102;
        /** @ts-ignore @type { | typeof __VLS_components.Check} */
        Check;
        // @ts-ignore
        const __VLS_103 = __VLS_asFunctionalComponent1(__VLS_102, new __VLS_102({
            // @ts-ignore
            size: (16),
        }));
        const __VLS_104 = __VLS_103({
            size: (16),
        }, ...__VLS_functionalComponentArgsRest(__VLS_103));
        ([
            __VLS_unwrap(t, {})("30 days", "30 天"),
            __VLS_unwrap(t, {})("90 days", "90 天"),
            __VLS_unwrap(t, {})("3 years", "3 年"),
        ][i]);
        (__VLS_unwrap(t, {})("data access", "数据访问"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
        let __VLS_107;
        /** @ts-ignore @type { | typeof __VLS_components.Check} */
        Check;
        // @ts-ignore
        const __VLS_108 = __VLS_asFunctionalComponent1(__VLS_107, new __VLS_107({
            // @ts-ignore
            size: (16),
        }));
        const __VLS_109 = __VLS_108({
            size: (16),
        }, ...__VLS_functionalComponentArgsRest(__VLS_108));
        __VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
        let __VLS_112;
        /** @ts-ignore @type { | typeof __VLS_components.Check} */
        Check;
        // @ts-ignore
        const __VLS_113 = __VLS_asFunctionalComponent1(__VLS_112, new __VLS_112({
            // @ts-ignore
            size: (16),
        }));
        const __VLS_114 = __VLS_113({
            size: (16),
        }, ...__VLS_functionalComponentArgsRest(__VLS_113));
        (__VLS_unwrap(t, {})("Stop at quota by default", "默认额度用尽后停止接收"));
        let __VLS_117;
        /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
        RouterLink;
        // @ts-ignore
        const __VLS_118 = __VLS_asFunctionalComponent1(__VLS_117, new __VLS_117({
            // @ts-ignore
            to: "/register", ...{ class: (['btn', i === 1 ? 'primary' : '']) },
        }));
        const __VLS_119 = __VLS_118({
            to: "/register",
            ...{ class: (['btn', i === 1 ? 'primary' : '']) },
        }, ...__VLS_functionalComponentArgsRest(__VLS_118));
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        const { default: __VLS_122 } = __VLS_nonNull(__VLS_120.slots);
        (__VLS_unwrap(t, {})("Get started", "开始使用"));
        let __VLS_123;
        /** @ts-ignore @type { | typeof __VLS_components.ArrowRight} */
        ArrowRight;
        // @ts-ignore
        const __VLS_124 = __VLS_asFunctionalComponent1(__VLS_123, new __VLS_123({
            // @ts-ignore
            size: (16),
        }));
        const __VLS_125 = __VLS_124({
            size: (16),
        }, ...__VLS_functionalComponentArgsRest(__VLS_124));
        // @ts-ignore
        [t, t, t, t, t, t, t, t, t, t, t, t, t, page,];
        var __VLS_120;
        // @ts-ignore
        [];
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
        ...{ class: "panel pricing-details" },
    });
    /** @type {__VLS_StyleScopedClasses['panel']} */ ;
    /** @type {__VLS_StyleScopedClasses['pricing-details']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
    (__VLS_unwrap(t, {})("Usage that stays in your control", "由你控制用量"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_unwrap(t, {})("Core and Pro can opt into metered overage with an email-confirmed monthly cap. Free must upgrade or wait for a new month.", "Core 和 Pro 可经邮箱验证开启超额计费并设置月度上限。Free 需升级或等待下个账期。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.table, __VLS_intrinsics.table)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.thead, __VLS_intrinsics.thead)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
    (__VLS_unwrap(t, {})("Monthly units", "当月累计 units"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
    (__VLS_unwrap(t, {})("Price per 100,000 units", "每 100,000 units 价格"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.tbody, __VLS_intrinsics.tbody)({});
    const __VLS_128 = __VLS_tryAsConstant(([
        ['100,001 – 1,000,000', '$8'],
        ['1,000,001 – 10,000,000', '$7'],
        ['10,000,001 – 50,000,000', '$6.50'],
        ['50,000,001 +', '$6'],
    ]));
    for (const [row] of __VLS_vFor(__VLS_nonNull(__VLS_128))) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
        (row[0]);
        __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
        (row[1]);
        // @ts-ignore
        [t, t, t, t,];
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
        ...{ class: "muted" },
    });
    /** @type {__VLS_StyleScopedClasses['muted']} */ ;
    (__VLS_unwrap(t, {})("Progressive rates, prorated to actual units. Core at 500,000 units = $29 + $32 = $61 before tax. Model provider costs are separate. Add-on packs are not yet available.", "分段累进，按实际 units 比例计费。Core 使用 500,000 units 时为 $29 + $32 = $61（税前）。模型供应商费用单独核算。加量包暂未开放。"));
}
else if (authPage.value) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.main, __VLS_intrinsics.main)({
        ...{ class: "auth-layout" },
    });
    /** @type {__VLS_StyleScopedClasses['auth-layout']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "auth-aside" },
    });
    /** @type {__VLS_StyleScopedClasses['auth-aside']} */ ;
    let __VLS_129;
    /** @ts-ignore @type { | typeof __VLS_components.Activity} */
    Activity;
    // @ts-ignore
    const __VLS_130 = __VLS_asFunctionalComponent1(__VLS_129, new __VLS_129({
        // @ts-ignore
        size: (40),
    }));
    const __VLS_131 = __VLS_130({
        size: (40),
    }, ...__VLS_functionalComponentArgsRest(__VLS_130));
    __VLS_asFunctionalElement1(__VLS_intrinsics.h1, __VLS_intrinsics.h1)({});
    (__VLS_unwrap(t, {})("Clarity for every", "洞察每一次"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.br)({});
    (__VLS_unwrap(t, {})("AI interaction.", "AI 交互。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_unwrap(t, {})("Trace performance, understand usage, and build with confidence.", "追踪性能、理解用量，更有把握地构建应用。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "auth-line" },
    });
    /** @type {__VLS_StyleScopedClasses['auth-line']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
        ...{ class: "auth-card" },
    });
    /** @type {__VLS_StyleScopedClasses['auth-card']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "eyebrow" },
    });
    /** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
    ({
        login: __VLS_unwrap(t, {})("Welcome back", "欢迎回来"),
        register: __VLS_unwrap(t, {})("Create your account", "创建账号"),
        verify: __VLS_unwrap(t, {})("Verify your email", "验证邮箱"),
        "forgot-password": __VLS_unwrap(t, {})("Reset your password", "找回密码"),
        "reset-password": __VLS_unwrap(t, {})("Choose a new password", "设置新密码"),
    }[page.value]);
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_unwrap(t, {})("Your workspace, one step closer.", "即将进入你的工作空间。"));
    if (error.value) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "notice error" },
            role: "alert",
        });
        /** @type {__VLS_StyleScopedClasses['notice']} */ ;
        /** @type {__VLS_StyleScopedClasses['error']} */ ;
        (error.value);
    }
    if (message.value) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "notice" },
        });
        /** @type {__VLS_StyleScopedClasses['notice']} */ ;
        (message.value);
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
        ...{ onSubmit: (submit) },
    });
    __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
    (__VLS_unwrap(t, {})("Email address", "邮箱地址"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
        type: "email",
        required: true,
        autocomplete: "email",
        placeholder: "you@company.com",
    });
    (form.value.email);
    if (['login', 'register', 'reset-password'].includes(page.value)) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        (__VLS_unwrap(t, {})("Password", "密码"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            type: "password",
            required: true,
            minlength: (page.value === 'login' ? 1 : 12),
            maxlength: "72",
            autocomplete: (page.value === 'login' ? 'current-password' : 'new-password'),
            placeholder: (__VLS_unwrap(t, {})('At least 12 characters', '至少 12 个字符')),
        });
        (form.value.password);
    }
    if (['verify', 'reset-password'].includes(page.value)) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        (__VLS_unwrap(t, {})("Challenge ID", "验证请求 ID"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            required: true,
        });
        (form.value.challengeId);
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        (__VLS_unwrap(t, {})("6-digit email code", "6 位邮箱验证码"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            inputmode: "numeric",
            pattern: "[0-9]{6}",
            maxlength: "6",
            required: true,
            autocomplete: "one-time-code",
        });
        (form.value.code);
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (resend) },
            ...{ class: "text-button" },
            type: "button",
        });
        /** @type {__VLS_StyleScopedClasses['text-button']} */ ;
        (__VLS_unwrap(t, {})("Send a new code", "重新发送验证码"));
    }
    if (page.value === 'register') {
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({
            ...{ class: "check-label" },
        });
        /** @type {__VLS_StyleScopedClasses['check-label']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            type: "checkbox",
            required: true,
        });
        (form.value.terms);
        (__VLS_unwrap(t, {})("I agree to the", "我同意"));
        let __VLS_134;
        /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
        RouterLink;
        // @ts-ignore
        const __VLS_135 = __VLS_asFunctionalComponent1(__VLS_134, new __VLS_134({
            // @ts-ignore
            to: "/terms",
        }));
        const __VLS_136 = __VLS_135({
            to: "/terms",
        }, ...__VLS_functionalComponentArgsRest(__VLS_135));
        const { default: __VLS_139 } = __VLS_nonNull(__VLS_137.slots);
        (__VLS_unwrap(t, {})("Terms of Service", "服务条款"));
        // @ts-ignore
        [t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, page, page, page, page, page, page, authPage, error, error, message, message, form, form, form, form, form,];
        var __VLS_137;
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        disabled: (busy.value),
        ...{ class: "btn primary full" },
    });
    /** @type {__VLS_StyleScopedClasses['btn']} */ ;
    /** @type {__VLS_StyleScopedClasses['primary']} */ ;
    /** @type {__VLS_StyleScopedClasses['full']} */ ;
    (busy.value
        ? __VLS_unwrap(t, {})("Please wait…", "请稍候…")
        : page.value === "login"
            ? __VLS_unwrap(t, {})("Sign in", "登录")
            : page.value === "register"
                ? __VLS_unwrap(t, {})("Create account", "创建账号")
                : __VLS_unwrap(t, {})("Continue", "继续"));
    let __VLS_140;
    /** @ts-ignore @type { | typeof __VLS_components.ArrowRight} */
    ArrowRight;
    // @ts-ignore
    const __VLS_141 = __VLS_asFunctionalComponent1(__VLS_140, new __VLS_140({
        // @ts-ignore
        size: (16),
    }));
    const __VLS_142 = __VLS_141({
        size: (16),
    }, ...__VLS_functionalComponentArgsRest(__VLS_141));
    if (page.value === 'login' || page.value === 'register') {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "divider" },
        });
        /** @type {__VLS_StyleScopedClasses['divider']} */ ;
        (__VLS_unwrap(t, {})("or", "或"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.a, __VLS_intrinsics.a)({
            href: "/api/auth/github",
            ...{ class: "btn full" },
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        /** @type {__VLS_StyleScopedClasses['full']} */ ;
        let __VLS_145;
        /** @ts-ignore @type { | typeof __VLS_components.GitBranch} */
        GitBranch;
        // @ts-ignore
        const __VLS_146 = __VLS_asFunctionalComponent1(__VLS_145, new __VLS_145({
            // @ts-ignore
            size: (17),
        }));
        const __VLS_147 = __VLS_146({
            size: (17),
        }, ...__VLS_functionalComponentArgsRest(__VLS_146));
        (__VLS_unwrap(t, {})("Continue with GitHub", "通过 GitHub 继续"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
            ...{ class: "auth-links" },
        });
        /** @type {__VLS_StyleScopedClasses['auth-links']} */ ;
        let __VLS_150;
        /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
        RouterLink;
        // @ts-ignore
        const __VLS_151 = __VLS_asFunctionalComponent1(__VLS_150, new __VLS_150({
            // @ts-ignore
            to: (page.value === 'login' ? '/register' : '/login'),
        }));
        const __VLS_152 = __VLS_151({
            to: (page.value === 'login' ? '/register' : '/login'),
        }, ...__VLS_functionalComponentArgsRest(__VLS_151));
        const { default: __VLS_155 } = __VLS_nonNull(__VLS_153.slots);
        (page.value === "login"
            ? __VLS_unwrap(t, {})("Create an account", "创建账号")
            : __VLS_unwrap(t, {})("Already have an account? Sign in", "已有账号？登录"));
        // @ts-ignore
        [t, t, t, t, t, t, t, t, page, page, page, page, page, page, busy, busy,];
        var __VLS_153;
        let __VLS_156;
        /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
        RouterLink;
        // @ts-ignore
        const __VLS_157 = __VLS_asFunctionalComponent1(__VLS_156, new __VLS_156({
            // @ts-ignore
            to: "/forgot-password",
        }));
        const __VLS_158 = __VLS_157({
            to: "/forgot-password",
        }, ...__VLS_functionalComponentArgsRest(__VLS_157));
        const { default: __VLS_161 } = __VLS_nonNull(__VLS_159.slots);
        (__VLS_unwrap(t, {})("Forgot password?", "忘记密码？"));
        // @ts-ignore
        [t,];
        var __VLS_159;
        if (page.value === 'login') {
            let __VLS_162;
            /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
            RouterLink;
            // @ts-ignore
            const __VLS_163 = __VLS_asFunctionalComponent1(__VLS_162, new __VLS_162({
                // @ts-ignore
                to: "/verify",
            }));
            const __VLS_164 = __VLS_163({
                to: "/verify",
            }, ...__VLS_functionalComponentArgsRest(__VLS_163));
            const { default: __VLS_167 } = __VLS_nonNull(__VLS_165.slots);
            (__VLS_unwrap(t, {})("Verify email", "验证邮箱"));
            // @ts-ignore
            [t, page,];
            var __VLS_165;
        }
    }
}
else if (page.value === 'docs') {
    __VLS_asFunctionalElement1(__VLS_intrinsics.main, __VLS_intrinsics.main)({
        ...{ class: "docs-layout" },
    });
    /** @type {__VLS_StyleScopedClasses['docs-layout']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.aside, __VLS_intrinsics.aside)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "eyebrow" },
    });
    /** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
    (__VLS_unwrap(t, {})("DEVELOPER DOCS", "开发者文档"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.a, __VLS_intrinsics.a)({
        href: "#quickstart",
    });
    (__VLS_unwrap(t, {})("Quickstart", "快速开始"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.a, __VLS_intrinsics.a)({
        href: "#protocol",
    });
    (__VLS_unwrap(t, {})("Batch protocol", "批次协议"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.a, __VLS_intrinsics.a)({
        href: "#sdk",
    });
    __VLS_asFunctionalElement1(__VLS_intrinsics.a, __VLS_intrinsics.a)({
        href: "#retries",
    });
    (__VLS_unwrap(t, {})("Errors & retries", "错误与重试"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.a, __VLS_intrinsics.a)({
        href: "#security",
    });
    (__VLS_unwrap(t, {})("Security", "安全"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.article, __VLS_intrinsics.article)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "eyebrow" },
    });
    /** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
    (__VLS_unwrap(t, {})("GET STARTED", "开始接入"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.h1, __VLS_intrinsics.h1)({
        id: "quickstart",
    });
    (__VLS_unwrap(t, {})("Your first trace, in minutes.", "几分钟内接入第一条 Trace。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_unwrap(t, {})("MeterTrail collects observability events asynchronously. Your application calls the model provider directly.", "MeterTrail 异步采集可观测事件，应用直接调用模型供应商。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.ol, __VLS_intrinsics.ol)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
    (__VLS_unwrap(t, {})("Create an account, verify your email, and create a project.", "创建账号、验证邮箱并创建项目。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
    (__VLS_unwrap(t, {})("Open Integration & API Keys. Verify the action by email to create a project key.", "进入接入与密钥，通过邮箱验证码创建项目密钥。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
    (__VLS_unwrap(t, {})("Set METERTRAIL_API_KEY and METERTRAIL_ENDPOINT to your deployment URL.", "设置 METERTRAIL_API_KEY 和 METERTRAIL_ENDPOINT 为实际部署地址。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
    (__VLS_unwrap(t, {})("Run scripts/send-example.mjs, then open the project overview.", "运行 scripts/send-example.mjs，再打开项目概览。"));
    const __VLS_168 = DocsExamples;
    // @ts-ignore
    const __VLS_169 = __VLS_asFunctionalComponent1(__VLS_168, new __VLS_168({
    // @ts-ignore
    }));
    const __VLS_170 = __VLS_169({}, ...__VLS_functionalComponentArgsRest(__VLS_169));
    __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({
        id: "protocol",
    });
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_unwrap(t, {})('Use schemaVersion "1.0", agent, and events. Each event needs eventId (UUID), type, objectId, revision, startedAt, and data. Trace and Observation also need traceId; Observation needs spanId.', '请求包含 schemaVersion "1.0"、agent 和 events。每条事件包含 eventId（UUID）、type、objectId、revision、startedAt、data；Trace 和 Observation 还需 traceId，Observation 需 spanId。'));
    __VLS_asFunctionalElement1(__VLS_intrinsics.table, __VLS_intrinsics.table)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.tbody, __VLS_intrinsics.tbody)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    (__VLS_unwrap(t, {})("Batch size", "批次大小"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    (__VLS_unwrap(t, {})("Time window", "时间窗口"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    (__VLS_unwrap(t, {})("Last 7 days; 5 minutes future tolerance", "最近 7 天；允许未来 5 分钟偏差"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    (__VLS_unwrap(t, {})("Durably stored. Poll GET /v1/batches/{batchId} for final results.", "已可靠写入，轮询 GET /v1/batches/{batchId} 获取最终结果。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    (__VLS_unwrap(t, {})("Nonnegative integers or null; cached input is part of total input.", "非负整数或 null；缓存输入是总输入的子集。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({
        id: "sdk",
    });
    (__VLS_unwrap(t, {})("Local SDK packages", "本地 SDK 包"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_unwrap(t, {})("Source SDKs are provided in sdk/python, sdk/node, and sdk/java. They are not published to package registries. Use the repository installation instructions.", "源码 SDK 位于 sdk/python、sdk/node、sdk/java，尚未发布到包仓库，请按仓库说明本地安装。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({
        id: "retries",
    });
    (__VLS_unwrap(t, {})("Retries preserve identity", "重试必须保留身份"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_unwrap(t, {})("Keep eventId, objectId, revision, and content unchanged. Back off with jitter on network errors and 5xx. Respect Retry-After on RATE_LIMITED. Pause on QUOTA_EXCEEDED or invalid keys. Split on 413. Inspect per-event errors after 202.", "重试保留 eventId、objectId、revision 和内容。网络错误或 5xx 指数退避并加抖动；RATE_LIMITED 遵循 Retry-After；额度不足或密钥无效暂停；413 拆批；202 后检查逐条结果。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({
        id: "security",
    });
    (__VLS_unwrap(t, {})("Collect only what you need", "仅采集必要数据"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_unwrap(t, {})("Never send API keys, passwords, complete prompts, or model responses in event data. Unknown usage and missing model prices remain unknown, never zero.", "不要在事件中发送 API Key、密码、完整提示词或模型回答。未知用量与缺失价格显示为未知，不会按零处理。"));
}
else {
    __VLS_asFunctionalElement1(__VLS_intrinsics.main, __VLS_intrinsics.main)({
        ...{ class: "legal public-content" },
    });
    /** @type {__VLS_StyleScopedClasses['legal']} */ ;
    /** @type {__VLS_StyleScopedClasses['public-content']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "eyebrow" },
    });
    /** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.h1, __VLS_intrinsics.h1)({});
    (page.value === "privacy"
        ? __VLS_unwrap(t, {})("Privacy policy", "隐私政策")
        : page.value === "refunds"
            ? __VLS_unwrap(t, {})("Refund policy", "退款政策")
            : __VLS_unwrap(t, {})("Terms of service", "服务条款"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "notice" },
    });
    /** @type {__VLS_StyleScopedClasses['notice']} */ ;
    (__VLS_unwrap(t, {})("Deployment policy template — the operator must complete legal entity, contact, jurisdiction, and payment details before public launch.", "部署政策模板：运营方需在公开上线前补充主体、联系方式、司法管辖与支付条款。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
    (__VLS_unwrap(t, {})("Data and service", "数据与服务"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_unwrap(t, {})("MeterTrail stores account information and the trace metadata you choose to submit. It does not require complete prompts or responses. Access to usage records follows your plan retention period.", "MeterTrail 保存账号信息和你主动上报的链路元数据，不要求完整提示词或回答，用量明细按套餐保留期限提供访问。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
    (__VLS_unwrap(t, {})("Billing and cancellation", "计费与取消"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_unwrap(t, {})("Monitoring units and model provider charges are separate. New objects stop at the quota unless you explicitly authorize overage. Refund, cancellation, tax, and dispute terms must be confirmed by the operator and the selected payment provider before accepting live payments.", "监控 units 与模型供应商费用单独核算。默认额度用尽停止接收新增对象，超额需明确授权。退款、取消、税费与争议规则需由运营方及支付平台确认后才能开启正式收款。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
    (__VLS_unwrap(t, {})("Account and deletion", "账号与删除"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_unwrap(t, {})("Sensitive changes require email verification. Project deletion removes analytics detail asynchronously; minimal billing and audit records may remain under the applicable retention policy.", "敏感变更需要邮箱验证。删除项目会异步删除分析明细；必要的账单和审计信息按适用保留政策留存。"));
}
__VLS_asFunctionalElement1(__VLS_intrinsics.footer, __VLS_intrinsics.footer)({});
let __VLS_173;
/** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
RouterLink;
// @ts-ignore
const __VLS_174 = __VLS_asFunctionalComponent1(__VLS_173, new __VLS_173({
    // @ts-ignore
    ...{ class: "brand" }, to: "/",
}));
const __VLS_175 = __VLS_174({
    ...{ class: "brand" },
    to: "/",
}, ...__VLS_functionalComponentArgsRest(__VLS_174));
/** @type {__VLS_StyleScopedClasses['brand']} */ ;
const { default: __VLS_178 } = __VLS_nonNull(__VLS_176.slots);
let __VLS_179;
/** @ts-ignore @type { | typeof __VLS_components.Activity} */
Activity;
// @ts-ignore
const __VLS_180 = __VLS_asFunctionalComponent1(__VLS_179, new __VLS_179({
    // @ts-ignore
    size: (20),
}));
const __VLS_181 = __VLS_180({
    size: (20),
}, ...__VLS_functionalComponentArgsRest(__VLS_180));
// @ts-ignore
[t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, page, page, page,];
var __VLS_176;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
(__VLS_unwrap(t, {})("Visibility for what you build.", "看清你构建的每一步。"));
__VLS_asFunctionalElement1(__VLS_intrinsics.nav, __VLS_intrinsics.nav)({});
let __VLS_184;
/** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
RouterLink;
// @ts-ignore
const __VLS_185 = __VLS_asFunctionalComponent1(__VLS_184, new __VLS_184({
    // @ts-ignore
    to: "/privacy",
}));
const __VLS_186 = __VLS_185({
    to: "/privacy",
}, ...__VLS_functionalComponentArgsRest(__VLS_185));
const { default: __VLS_189 } = __VLS_nonNull(__VLS_187.slots);
(__VLS_unwrap(t, {})("Privacy", "隐私"));
// @ts-ignore
[t, t,];
var __VLS_187;
let __VLS_190;
/** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
RouterLink;
// @ts-ignore
const __VLS_191 = __VLS_asFunctionalComponent1(__VLS_190, new __VLS_190({
    // @ts-ignore
    to: "/terms",
}));
const __VLS_192 = __VLS_191({
    to: "/terms",
}, ...__VLS_functionalComponentArgsRest(__VLS_191));
const { default: __VLS_195 } = __VLS_nonNull(__VLS_193.slots);
(__VLS_unwrap(t, {})("Terms", "条款"));
// @ts-ignore
[t,];
var __VLS_193;
let __VLS_196;
/** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
RouterLink;
// @ts-ignore
const __VLS_197 = __VLS_asFunctionalComponent1(__VLS_196, new __VLS_196({
    // @ts-ignore
    to: "/refunds",
}));
const __VLS_198 = __VLS_197({
    to: "/refunds",
}, ...__VLS_functionalComponentArgsRest(__VLS_197));
const { default: __VLS_201 } = __VLS_nonNull(__VLS_199.slots);
(__VLS_unwrap(t, {})("Refunds", "退款"));
// @ts-ignore
[t,];
var __VLS_199;
__VLS_asFunctionalElement1(__VLS_intrinsics.a, __VLS_intrinsics.a)({
    href: "https://github.com/zhiyuan213/llm-usage-monitor",
    target: "_blank",
    rel: "noreferrer",
});
// @ts-ignore
[];
const __VLS_export = (await import('vue')).defineComponent({});
export default {};
