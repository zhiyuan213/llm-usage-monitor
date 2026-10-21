import WorkspaceOverview from "./WorkspaceOverview.vue";
import ProjectInfoEditor from "./ProjectInfoEditor.vue";
import BatchHistory from "./BatchHistory.vue";
import AccountSecurity from "./AccountSecurity.vue";
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Activity, LayoutDashboard, FolderKanban, GitBranch, Users, KeyRound, Bell, CreditCard, Settings, ScrollText, ArrowUpRight, Plus, RefreshCw, Search, ChevronRight, LogOut, BookOpen, Copy, Check, X, ShieldCheck, Clock, ArrowLeft, BarChart3, } from "lucide-vue-next";
import { api, t, n, money, date, session, loadSession, errorText } from "./lib";
import { Layers as LayersIcon, Coins as CoinsIcon, Code2 as CodeIcon, } from "lucide-vue-next";
export default {};
;
const __VLS_self = (await import('vue')).defineComponent({ components: { LayersIcon, CoinsIcon, CodeIcon } });
const __VLS_export = await (async () => {
    const route = useRoute(), router = useRouter(), page = computed(() => String(route.params.page || "overview"));
    const nav = [
        ["workspace", "Workspace", "工作空间", LayoutDashboard],
        ["overview", "Overview", "概览", LayoutDashboard],
        ["projects", "Projects", "项目", FolderKanban],
        ["traces", "Traces", "调用记录", GitBranch],
        ["analytics", "Users & customers", "用户与客户", Users],
        ["keys", "Integration & API keys", "接入与密钥", KeyRound],
        ["alerts", "Alerts", "告警", Bell],
        ["billing", "Billing", "套餐与账单", CreditCard],
        ["settings", "Project settings", "项目设置", Settings],
        ["account", "Account & security", "账号与安全", ShieldCheck],
        ["audit", "Audit logs", "审计日志", ScrollText],
    ];
    const title = computed(() => {
        const item = nav.find((v) => v[0] === page.value);
        return item ? t(item[1], item[2]) : t("Trace detail", "调用详情");
    });
    const projects = ref([]), selected = ref(localStorage.getItem("metertrail.project") || ""), loading = ref(true), error = ref(""), notice = ref(""), data = ref({}), overview = ref({}), recent = ref([]);
    const project = computed(() => projects.value.find((p) => p.id === selected.value));
    const search = ref(""), status = ref(""), tracePage = ref(0), dimension = ref("customerId"), showCreate = ref(false), createdKey = ref(""), keyName = ref("Production key"), copied = ref(false);
    const newProject = reactive({ name: "", environment: "production" }), alert = reactive({ name: "", metric: "error_count", threshold: "10" }), price = reactive({
        provider: "",
        model: "",
        effectiveAt: new Date().toISOString().slice(0, 16),
        inputRate: "",
        outputRate: "",
        cachedRate: "",
    }), billing = reactive({ enabled: false, cap: "0", consent: false }), deleteConfirm = ref("");
    const verification = reactive({
        open: false,
        action: "",
        target: "",
        challengeId: "",
        code: "",
        busy: false,
        error: "",
    });
    let perform = async () => { };
    async function verify(action, target, fn) {
        verification.open = true;
        verification.action = action;
        verification.target = target;
        verification.code = "";
        verification.error = "";
        verification.challengeId = "";
        perform = fn;
        await sendCode();
    }
    async function sendCode() {
        verification.busy = true;
        try {
            const r = await api("/verification", {
                action: verification.action,
                target: verification.target,
            });
            verification.challengeId = r.challengeId;
        }
        catch (e) {
            verification.error = errorText(e);
        }
        finally {
            verification.busy = false;
        }
    }
    async function confirm() {
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
        }
        catch (e) {
            verification.error = errorText(e);
        }
        finally {
            verification.busy = false;
        }
    }
    async function run(fn) {
        error.value = "";
        try {
            await fn();
        }
        catch (e) {
            error.value = errorText(e);
        }
    }
    const navigate = (url) => window.location.assign(url);
    const path = (suffix) => "/projects/" + selected.value + suffix;
    async function refresh() {
        loading.value = true;
        error.value = "";
        try {
            projects.value = await api("/projects");
            session.value = await api("/me");
            if (!projects.value.some((p) => p.id === selected.value))
                selected.value = projects.value[0]?.id || "";
            localStorage.setItem("metertrail.project", selected.value);
            if (page.value === "billing") {
                data.value = await api("/billing");
                billing.enabled = data.value.workspace.auto_overage;
                billing.cap = String(data.value.workspace.overage_cap);
            }
            else if (page.value === "audit")
                data.value = await api("/audit");
            else if (page.value === "account")
                await loadSession();
            else if (selected.value) {
                if (page.value === "overview") {
                    const [o, r] = await Promise.all([
                        api(path("/overview")),
                        api(path("/objects")),
                    ]);
                    overview.value = o;
                    recent.value = r;
                }
                else if (page.value === "traces")
                    data.value = await api(path("/traces") +
                        "?" +
                        new URLSearchParams({
                            search: search.value,
                            status: status.value,
                            page: String(tracePage.value),
                        }));
                else if (page.value === "trace")
                    data.value = await api(path("/traces/" + route.params.detail));
                else if (page.value === "analytics")
                    data.value = await api(path("/analytics") + "?dimension=" + dimension.value);
                else if (page.value === "keys")
                    data.value = await api(path("/keys"));
                else if (page.value === "alerts")
                    data.value = await api(path("/alerts"));
                else if (page.value === "settings")
                    data.value = await api(path("/prices"));
            }
        }
        catch (e) {
            error.value = errorText(e);
        }
        finally {
            loading.value = false;
        }
    }
    onMounted(async () => {
        if (!(await loadSession())) {
            router.replace("/login");
            return;
        }
        await refresh();
    });
    watch(() => route.fullPath, () => {
        data.value = {};
        notice.value = "";
        createdKey.value = "";
        refresh();
    });
    async function changeProject() {
        tracePage.value = 0;
        await refresh();
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
    async function createKey() {
        const p = selected.value, name = keyName.value;
        await verify("key.create", p + ":" + name, async (proof) => {
            const result = await api("/projects/" + p + "/keys", { ...proof, name });
            createdKey.value = result.key;
        });
    }
    async function revoke(key) {
        const p = selected.value;
        await verify("key.revoke", p + ":" + key, async (proof) => {
            await api("/projects/" + p + "/keys/" + key + "/revoke", proof);
        });
    }
    async function saveOverage() {
        const cap = Number(billing.cap).toString(), enabled = billing.enabled, consent = billing.consent;
        await verify("billing.overage", enabled + ":" + cap, async (proof) => {
            await api("/billing/overage", { ...proof, cap, enabled, consent });
        });
    }
    async function deleteProject() {
        const p = selected.value, confirm = deleteConfirm.value;
        await verify("project.delete", p, async (proof) => {
            await api("/projects/" + p + "/delete", { ...proof, confirm });
            selected.value = "";
            router.push("/app/projects");
        });
    }
    async function copyKey() {
        await navigator.clipboard.writeText(createdKey.value);
        copied.value = true;
    }
    async function logout() {
        await api("/logout", {});
        session.value = null;
        router.push("/login");
    }
    const daily = computed(() => [...(overview.value.daily || [])].reverse()), maxCalls = computed(() => Math.max(1, ...daily.value.map((d) => d.calls)));
    const traceObjects = computed(() => [...(data.value.objects || [])].sort((a, b) => new Date(a.started_at).getTime() - new Date(b.started_at).getTime() ||
        (a.type === "trace" ? -1 : b.type === "trace" ? 1 : 0)));
    const traceStart = computed(() => Math.min(...traceObjects.value
        .filter((o) => o.type !== "score")
        .map((o) => new Date(o.started_at).getTime())));
    const traceDuration = computed(() => Math.max(1, ...traceObjects.value
        .filter((o) => o.type !== "score")
        .map((o) => new Date(o.started_at).getTime() +
        (o.data.durationMs || 0) -
        traceStart.value)));
    const usagePercent = computed(() => Math.min(100, (100 * Number(session.value?.workspace.used_units || 0)) /
        (session.value?.workspace.plan === "Free" ? 50000 : 100000)));
    const __VLS_ctx = {};
    const __VLS_componentsOption = { LayersIcon, CoinsIcon, CodeIcon };
    let __VLS_components;
    let __VLS_intrinsics;
    let __VLS_directives;
    void __VLS_ctx, __VLS_components, __VLS_intrinsics, __VLS_directives;
    // @ts-ignore
    __VLS_withDotValue(session, {});
    // @ts-ignore
    __VLS_withDotValue(page, {});
    // @ts-ignore
    __VLS_withDotValue(projects, {});
    // @ts-ignore
    __VLS_withDotValue(error, {});
    // @ts-ignore
    __VLS_withDotValue(notice, {});
    // @ts-ignore
    __VLS_withDotValue(loading, {});
    // @ts-ignore
    __VLS_withDotValue(showCreate, {});
    // @ts-ignore
    __VLS_withDotValue(selected, {});
    // @ts-ignore
    __VLS_withDotValue(router, {});
    // @ts-ignore
    __VLS_withDotValue(project, {});
    // @ts-ignore
    __VLS_withDotValue(overview, {});
    // @ts-ignore
    __VLS_withDotValue(daily, {});
    // @ts-ignore
    __VLS_withDotValue(recent, {});
    // @ts-ignore
    __VLS_withDotValue(tracePage, {});
    // @ts-ignore
    __VLS_withDotValue(data, {});
    // @ts-ignore
    __VLS_withDotValue(traceObjects, {});
    // @ts-ignore
    __VLS_withDotValue(route, {});
    // @ts-ignore
    __VLS_withDotValue(dimension, {});
    // @ts-ignore
    __VLS_withDotValue(createdKey, {});
    // @ts-ignore
    __VLS_withDotValue(copied, {});
    // @ts-ignore
    __VLS_withDotValue(alert, {});
    // @ts-ignore
    __VLS_withDotValue(billing, {});
    // @ts-ignore
    __VLS_withDotValue(price, {});
    // @ts-ignore
    __VLS_withDotValue(deleteConfirm, {});
    // @ts-ignore
    __VLS_withDotValue(newProject, {});
    // @ts-ignore
    __VLS_withDotValue(verification, {});
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "console-shell" },
    });
    /** @type {__VLS_StyleScopedClasses['console-shell']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.aside, __VLS_intrinsics.aside)({
        ...{ class: "sidebar" },
    });
    /** @type {__VLS_StyleScopedClasses['sidebar']} */ ;
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
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "workspace-label" },
    });
    /** @type {__VLS_StyleScopedClasses['workspace-label']} */ ;
    (__VLS_unwrap(t, {})("WORKSPACE", "工作空间"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "workspace-switch" },
    });
    /** @type {__VLS_StyleScopedClasses['workspace-switch']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "avatar" },
    });
    /** @type {__VLS_StyleScopedClasses['avatar']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    (session.value?.workspace.name || "My workspace");
    __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
    (session.value?.workspace.plan || "Free");
    (__VLS_unwrap(t, {})("plan", "套餐"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.nav, __VLS_intrinsics.nav)({});
    const __VLS_11 = __VLS_tryAsConstant((__VLS_unwrap(nav, {})));
    for (const [item] of __VLS_vFor(__VLS_nonNull(__VLS_11))) {
        let __VLS_12;
        /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
        RouterLink;
        // @ts-ignore
        const __VLS_13 = __VLS_asFunctionalComponent1(__VLS_12, new __VLS_12({
            // @ts-ignore
            key: (item[0]), to: ('/app/' + item[0]), 'aria-label': (__VLS_unwrap(t, {})(item[1], item[2])), title: (__VLS_unwrap(t, {})(item[1], item[2])), ...{ class: ({ active: page.value === item[0] || (page.value === 'trace' && item[0] === 'traces'), }) },
        }));
        const __VLS_14 = __VLS_13({
            key: (item[0]),
            to: ('/app/' + item[0]),
            'aria-label': (__VLS_unwrap(t, {})(item[1], item[2])),
            title: (__VLS_unwrap(t, {})(item[1], item[2])),
            ...{ class: ({
                    active: page.value === item[0] || (page.value === 'trace' && item[0] === 'traces'),
                }) },
        }, ...__VLS_functionalComponentArgsRest(__VLS_13));
        /** @type {__VLS_StyleScopedClasses['active']} */ ;
        const { default: __VLS_17 } = __VLS_nonNull(__VLS_15.slots);
        const __VLS_18 = (item[3]);
        // @ts-ignore
        const __VLS_19 = __VLS_asFunctionalComponent1(__VLS_18, new __VLS_18({
            // @ts-ignore
            size: (17),
        }));
        const __VLS_20 = __VLS_19({
            size: (17),
        }, ...__VLS_functionalComponentArgsRest(__VLS_19));
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
        (__VLS_unwrap(t, {})(item[1], item[2]));
        // @ts-ignore
        [t, t, t, t, t, session, session, nav, page, page,];
        var __VLS_15;
        // @ts-ignore
        [];
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "sidebar-bottom" },
    });
    /** @type {__VLS_StyleScopedClasses['sidebar-bottom']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "usage-label" },
    });
    /** @type {__VLS_StyleScopedClasses['usage-label']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    (__VLS_unwrap(t, {})("Monthly units", "本月 units"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.b, __VLS_intrinsics.b)({});
    (__VLS_unwrap(n, {})(session.value?.workspace.used_units || 0));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "progress" },
    });
    /** @type {__VLS_StyleScopedClasses['progress']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.i, __VLS_intrinsics.i)({
        ...{ style: ({ width: __VLS_unwrap(usagePercent, {}) + '%' }) },
    });
    __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
    (__VLS_unwrap(t, {})("Stop at quota enabled", "默认额度保护已启用"));
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
    let __VLS_29;
    /** @ts-ignore @type { | typeof __VLS_components.BookOpen} */
    BookOpen;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent1(__VLS_29, new __VLS_29({
        // @ts-ignore
        size: (16),
    }));
    const __VLS_31 = __VLS_30({
        size: (16),
    }, ...__VLS_functionalComponentArgsRest(__VLS_30));
    (__VLS_unwrap(t, {})("Documentation", "开发文档"));
    let __VLS_34;
    /** @ts-ignore @type { | typeof __VLS_components.ArrowUpRight} */
    ArrowUpRight;
    // @ts-ignore
    const __VLS_35 = __VLS_asFunctionalComponent1(__VLS_34, new __VLS_34({
        // @ts-ignore
        size: (14),
    }));
    const __VLS_36 = __VLS_35({
        size: (14),
    }, ...__VLS_functionalComponentArgsRest(__VLS_35));
    // @ts-ignore
    [t, t, t, session, n, usagePercent,];
    var __VLS_26;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "account-summary" },
    });
    /** @type {__VLS_StyleScopedClasses['account-summary']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "avatar" },
    });
    /** @type {__VLS_StyleScopedClasses['avatar']} */ ;
    (session.value?.email?.[0]?.toUpperCase());
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    (session.value?.email);
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: // @ts-ignore
            (...[$event]) => {
                void $event;
                return (run(logout));
                // @ts-ignore
                [session, session,];
            } },
        ...{ class: "icon-button" },
        title: (__VLS_unwrap(t, {})('Sign out', '退出登录')),
    });
    /** @type {__VLS_StyleScopedClasses['icon-button']} */ ;
    let __VLS_39;
    /** @ts-ignore @type { | typeof __VLS_components.LogOut} */
    LogOut;
    // @ts-ignore
    const __VLS_40 = __VLS_asFunctionalComponent1(__VLS_39, new __VLS_39({
        // @ts-ignore
        size: (16),
    }));
    const __VLS_41 = __VLS_40({
        size: (16),
    }, ...__VLS_functionalComponentArgsRest(__VLS_40));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "console-main" },
    });
    /** @type {__VLS_StyleScopedClasses['console-main']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.header, __VLS_intrinsics.header)({
        ...{ class: "console-header" },
    });
    /** @type {__VLS_StyleScopedClasses['console-header']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "breadcrumb" },
    });
    /** @type {__VLS_StyleScopedClasses['breadcrumb']} */ ;
    (__VLS_unwrap(t, {})("Workspace", "工作空间"));
    let __VLS_44;
    /** @ts-ignore @type { | typeof __VLS_components.ChevronRight} */
    ChevronRight;
    // @ts-ignore
    const __VLS_45 = __VLS_asFunctionalComponent1(__VLS_44, new __VLS_44({
        // @ts-ignore
        size: (13),
    }));
    const __VLS_46 = __VLS_45({
        size: (13),
    }, ...__VLS_functionalComponentArgsRest(__VLS_45));
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    (__VLS_unwrap(title, {}));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "header-status" },
    });
    /** @type {__VLS_StyleScopedClasses['header-status']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "live-dot" },
    });
    /** @type {__VLS_StyleScopedClasses['live-dot']} */ ;
    (__VLS_unwrap(t, {})("All times in UTC", "时间均为 UTC"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.main, __VLS_intrinsics.main)({
        ...{ class: "console-content" },
    });
    /** @type {__VLS_StyleScopedClasses['console-content']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "page-heading" },
    });
    /** @type {__VLS_StyleScopedClasses['page-heading']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "eyebrow" },
    });
    /** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
    (page.value === "overview"
        ? __VLS_unwrap(t, {})("YOUR AI, AT A GLANCE", "AI 应用，一目了然")
        : "METERTRAIL CONSOLE");
    __VLS_asFunctionalElement1(__VLS_intrinsics.h1, __VLS_intrinsics.h1)({});
    (__VLS_unwrap(title, {}));
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (page.value === "overview"
        ? __VLS_unwrap(t, {})("A clear view of performance, usage, and cost.", "清晰掌握性能、用量与成本。")
        : __VLS_unwrap(t, {})("Manage your workspace with confidence.", "掌控工作空间的每一项配置。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "toolbar" },
    });
    /** @type {__VLS_StyleScopedClasses['toolbar']} */ ;
    if (!['billing', 'audit', 'account', 'workspace'].includes(page.value) &&
        projects.value.length) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.select, __VLS_intrinsics.select)({
            ...{ onChange: (changeProject) },
            value: (selected.value),
            'aria-label': "Project",
        });
        const __VLS_49 = __VLS_tryAsConstant((projects.value));
        for (const [p] of __VLS_vFor(__VLS_nonNull(__VLS_49))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.option, __VLS_intrinsics.option)({
                value: (p.id),
            });
            (p.name);
            // @ts-ignore
            [t, t, t, t, t, t, page, page, page, title, title, projects, projects, selected,];
        }
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (refresh) },
        ...{ class: "btn" },
        disabled: (loading.value),
    });
    /** @type {__VLS_StyleScopedClasses['btn']} */ ;
    let __VLS_50;
    /** @ts-ignore @type { | typeof __VLS_components.RefreshCw} */
    RefreshCw;
    // @ts-ignore
    const __VLS_51 = __VLS_asFunctionalComponent1(__VLS_50, new __VLS_50({
        // @ts-ignore
        size: (15), ...{ class: ({ spin: loading.value }) },
    }));
    const __VLS_52 = __VLS_51({
        size: (15),
        ...{ class: ({ spin: loading.value }) },
    }, ...__VLS_functionalComponentArgsRest(__VLS_51));
    /** @type {__VLS_StyleScopedClasses['spin']} */ ;
    (__VLS_unwrap(t, {})("Refresh", "刷新"));
    if (error.value) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "notice error" },
            role: "alert",
        });
        /** @type {__VLS_StyleScopedClasses['notice']} */ ;
        /** @type {__VLS_StyleScopedClasses['error']} */ ;
        (error.value);
    }
    if (notice.value) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "notice success" },
        });
        /** @type {__VLS_StyleScopedClasses['notice']} */ ;
        /** @type {__VLS_StyleScopedClasses['success']} */ ;
        (notice.value);
    }
    if (loading.value) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "loading-line" },
        });
        /** @type {__VLS_StyleScopedClasses['loading-line']} */ ;
    }
    if (page.value === 'projects') {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "section-heading" },
        });
        /** @type {__VLS_StyleScopedClasses['section-heading']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Your projects", "你的项目"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "count" },
        });
        /** @type {__VLS_StyleScopedClasses['count']} */ ;
        (projects.value.length);
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: // @ts-ignore
                (...[$event]) => {
                    void $event;
                    if (!(page.value === 'projects'))
                        throw 0;
                    return (showCreate.value = true);
                    // @ts-ignore
                    [t, t, page, projects, loading, loading, loading, error, error, notice, notice, showCreate,];
                } },
            ...{ class: "btn primary" },
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        /** @type {__VLS_StyleScopedClasses['primary']} */ ;
        let __VLS_55;
        /** @ts-ignore @type { | typeof __VLS_components.Plus} */
        Plus;
        // @ts-ignore
        const __VLS_56 = __VLS_asFunctionalComponent1(__VLS_55, new __VLS_55({
            // @ts-ignore
            size: (16),
        }));
        const __VLS_57 = __VLS_56({
            size: (16),
        }, ...__VLS_functionalComponentArgsRest(__VLS_56));
        (__VLS_unwrap(t, {})("New project", "新建项目"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "project-grid" },
        });
        /** @type {__VLS_StyleScopedClasses['project-grid']} */ ;
        const __VLS_60 = __VLS_tryAsConstant((projects.value));
        for (const [p] of __VLS_vFor(__VLS_nonNull(__VLS_60))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
                ...{ onClick: // @ts-ignore
                    (...[$event]) => {
                        void $event;
                        if (!(page.value === 'projects'))
                            throw 0;
                        selected.value = p.id;
                        router.value.push('/app/overview');
                        ;
                        // @ts-ignore
                        [t, projects, selected, router,];
                    } },
                ...{ class: "panel project-card" },
            });
            /** @type {__VLS_StyleScopedClasses['panel']} */ ;
            /** @type {__VLS_StyleScopedClasses['project-card']} */ ;
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
            let __VLS_61;
            /** @ts-ignore @type { | typeof __VLS_components.FolderKanban} */
            FolderKanban;
            // @ts-ignore
            const __VLS_62 = __VLS_asFunctionalComponent1(__VLS_61, new __VLS_61({
                // @ts-ignore
                size: (24),
            }));
            const __VLS_63 = __VLS_62({
                size: (24),
            }, ...__VLS_functionalComponentArgsRest(__VLS_62));
            let __VLS_66;
            /** @ts-ignore @type { | typeof __VLS_components.ArrowUpRight} */
            ArrowUpRight;
            // @ts-ignore
            const __VLS_67 = __VLS_asFunctionalComponent1(__VLS_66, new __VLS_66({
                // @ts-ignore
                size: (18),
            }));
            const __VLS_68 = __VLS_67({
                size: (18),
            }, ...__VLS_functionalComponentArgsRest(__VLS_67));
            __VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
            (p.name);
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                ...{ class: "badge success" },
            });
            /** @type {__VLS_StyleScopedClasses['badge']} */ ;
            /** @type {__VLS_StyleScopedClasses['success']} */ ;
            (p.environment);
            __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
            (__VLS_unwrap(n, {})(p.object_count));
            (__VLS_unwrap(t, {})("objects", "个对象"));
            __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
            (__VLS_unwrap(date, {})(p.created_at));
            // @ts-ignore
            [t, n, date,];
        }
    }
    if (!loading.value &&
        !selected.value &&
        !['billing', 'audit', 'account'].includes(page.value)) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "panel empty" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        /** @type {__VLS_StyleScopedClasses['empty']} */ ;
        let __VLS_71;
        /** @ts-ignore @type { | typeof __VLS_components.FolderKanban} */
        FolderKanban;
        // @ts-ignore
        const __VLS_72 = __VLS_asFunctionalComponent1(__VLS_71, new __VLS_71({
            // @ts-ignore
            size: (42),
        }));
        const __VLS_73 = __VLS_72({
            size: (42),
        }, ...__VLS_functionalComponentArgsRest(__VLS_72));
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Your first project starts here", "从第一个项目开始"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
        (__VLS_unwrap(t, {})("Create a project, generate an API key, and send your first trace.", "创建项目、生成密钥，然后发送第一条 Trace。"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: // @ts-ignore
                (...[$event]) => {
                    void $event;
                    if (!(!loading.value &&
                        !selected.value &&
                        !['billing', 'audit', 'account'].includes(page.value)))
                        throw 0;
                    return (showCreate.value = true);
                    // @ts-ignore
                    [t, t, page, selected, loading, showCreate,];
                } },
            ...{ class: "btn primary" },
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        /** @type {__VLS_StyleScopedClasses['primary']} */ ;
        let __VLS_76;
        /** @ts-ignore @type { | typeof __VLS_components.Plus} */
        Plus;
        // @ts-ignore
        const __VLS_77 = __VLS_asFunctionalComponent1(__VLS_76, new __VLS_76({
            // @ts-ignore
            size: (16),
        }));
        const __VLS_78 = __VLS_77({
            size: (16),
        }, ...__VLS_functionalComponentArgsRest(__VLS_77));
        (__VLS_unwrap(t, {})("Create project", "创建项目"));
    }
    if (page.value === 'overview' && selected.value) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "project-caption" },
        });
        /** @type {__VLS_StyleScopedClasses['project-caption']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "project-symbol" },
        });
        /** @type {__VLS_StyleScopedClasses['project-symbol']} */ ;
        let __VLS_81;
        /** @ts-ignore @type { | typeof __VLS_components.Activity} */
        Activity;
        // @ts-ignore
        const __VLS_82 = __VLS_asFunctionalComponent1(__VLS_81, new __VLS_81({
            // @ts-ignore
            size: (17),
        }));
        const __VLS_83 = __VLS_82({
            size: (17),
        }, ...__VLS_functionalComponentArgsRest(__VLS_82));
        __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
        (project.value?.name);
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "badge success" },
        });
        /** @type {__VLS_StyleScopedClasses['badge']} */ ;
        /** @type {__VLS_StyleScopedClasses['success']} */ ;
        (project.value?.environment);
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "muted" },
        });
        /** @type {__VLS_StyleScopedClasses['muted']} */ ;
        (__VLS_unwrap(t, {})("Retained data · UTC", "保留期内数据 · UTC"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "metrics" },
        });
        /** @type {__VLS_StyleScopedClasses['metrics']} */ ;
        const __VLS_86 = __VLS_tryAsConstant(([
            [
                __VLS_unwrap(t, {})('Model calls', '模型调用'),
                __VLS_unwrap(n, {})(overview.value.summary?.calls),
                __VLS_unwrap(t, {})('Generation observations', '模型生成步骤'),
            ],
            [
                __VLS_unwrap(t, {})('Total tokens', '总 Token'),
                __VLS_unwrap(n, {})(overview.value.summary?.tokens),
                __VLS_unwrap(t, {})('Known input + output', '已知输入 + 输出'),
            ],
            [
                __VLS_unwrap(t, {})('Estimated model cost', '模型估算成本'),
                __VLS_unwrap(money, {})(overview.value.summary?.cost),
                (overview.value.summary?.unknown_costs || 0) +
                    ' ' +
                    __VLS_unwrap(t, {})('calls with unknown cost', '次调用成本未知'),
            ],
            [
                __VLS_unwrap(t, {})('P95 latency', 'P95 延迟'),
                __VLS_unwrap(n, {})(overview.value.summary?.p95) + ' ms',
                __VLS_unwrap(t, {})('Across retained objects', '保留期内所有对象'),
            ],
        ]));
        for (const [v, i] of __VLS_vFor(__VLS_nonNull(__VLS_86))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.article, __VLS_intrinsics.article)({
                ...{ class: "panel metric" },
                key: (i),
            });
            /** @type {__VLS_StyleScopedClasses['panel']} */ ;
            /** @type {__VLS_StyleScopedClasses['metric']} */ ;
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
            (v[0]);
            const __VLS_87 = ([__VLS_unwrap(Activity, {}), __VLS_unwrap(LayersIcon, {}), __VLS_unwrap(BarChart3, {}), __VLS_unwrap(Clock, {})][i] || __VLS_unwrap(BarChart3, {}));
            // @ts-ignore
            const __VLS_88 = __VLS_asFunctionalComponent1(__VLS_87, new __VLS_87({
                // @ts-ignore
                size: (18),
            }));
            const __VLS_89 = __VLS_88({
                size: (18),
            }, ...__VLS_functionalComponentArgsRest(__VLS_88));
            __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
            (v[1]);
            __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
            (v[2]);
            // @ts-ignore
            [t, t, t, t, t, t, t, t, t, t, page, n, n, n, selected, project, project, overview, overview, overview, overview, overview, money, Activity, LayersIcon, BarChart3, BarChart3, Clock,];
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "chart-grid" },
        });
        /** @type {__VLS_StyleScopedClasses['chart-grid']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "panel-heading" },
        });
        /** @type {__VLS_StyleScopedClasses['panel-heading']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Activity over time", "调用趋势"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "legend" },
        });
        /** @type {__VLS_StyleScopedClasses['legend']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.i, __VLS_intrinsics.i)({});
        (__VLS_unwrap(t, {})("Objects per day", "每日对象数"));
        if (daily.value.length) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "real-chart" },
            });
            /** @type {__VLS_StyleScopedClasses['real-chart']} */ ;
            const __VLS_92 = __VLS_tryAsConstant((daily.value));
            for (const [d] of __VLS_vFor(__VLS_nonNull(__VLS_92))) {
                __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                    key: (d.day),
                    ...{ class: "chart-column" },
                });
                /** @type {__VLS_StyleScopedClasses['chart-column']} */ ;
                __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
                (__VLS_unwrap(n, {})(d.calls));
                __VLS_asFunctionalElement1(__VLS_intrinsics.i, __VLS_intrinsics.i)({
                    ...{ style: ({
                            height: Math.max(3, (150 * d.calls) / __VLS_unwrap(maxCalls, {})) + 'px',
                        }) },
                    title: (d.day + ': ' + d.calls),
                });
                __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
                (String(d.day).slice(5, 10));
                // @ts-ignore
                [t, t, n, daily, daily, maxCalls,];
            }
        }
        else {
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "chart-empty" },
            });
            /** @type {__VLS_StyleScopedClasses['chart-empty']} */ ;
            let __VLS_93;
            /** @ts-ignore @type { | typeof __VLS_components.BarChart3} */
            BarChart3;
            // @ts-ignore
            const __VLS_94 = __VLS_asFunctionalComponent1(__VLS_93, new __VLS_93({
                // @ts-ignore
                size: (34),
            }));
            const __VLS_95 = __VLS_94({
                size: (34),
            }, ...__VLS_functionalComponentArgsRest(__VLS_94));
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
            (__VLS_unwrap(t, {})("Your activity will appear after the first batch.", "首批数据处理后将在这里显示趋势。"));
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "panel-heading" },
        });
        /** @type {__VLS_StyleScopedClasses['panel-heading']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Cost by model", "模型成本分布"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "muted" },
        });
        /** @type {__VLS_StyleScopedClasses['muted']} */ ;
        if (overview.value.models?.length) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "model-list" },
            });
            /** @type {__VLS_StyleScopedClasses['model-list']} */ ;
            const __VLS_98 = __VLS_tryAsConstant((overview.value.models));
            for (const [m, i] of __VLS_vFor(__VLS_nonNull(__VLS_98))) {
                __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                    key: (m.model),
                });
                __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                    ...{ class: "model-dot" },
                    ...{ style: ({
                            background: ['#0052d9', '#19a6bb', '#7f72df'][Number(i) % 3],
                        }) },
                });
                /** @type {__VLS_StyleScopedClasses['model-dot']} */ ;
                __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
                (m.model);
                __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
                (__VLS_unwrap(n, {})(m.calls));
                (__VLS_unwrap(t, {})("calls", "次调用"));
                __VLS_asFunctionalElement1(__VLS_intrinsics.b, __VLS_intrinsics.b)({});
                (__VLS_unwrap(money, {})(m.cost));
                // @ts-ignore
                [t, t, t, n, overview, overview, money,];
            }
        }
        else {
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "chart-empty" },
            });
            /** @type {__VLS_StyleScopedClasses['chart-empty']} */ ;
            let __VLS_99;
            /** @ts-ignore @type { | typeof __VLS_components.CoinsIcon} */
            CoinsIcon;
            // @ts-ignore
            const __VLS_100 = __VLS_asFunctionalComponent1(__VLS_99, new __VLS_99({
            // @ts-ignore
            }));
            const __VLS_101 = __VLS_100({}, ...__VLS_functionalComponentArgsRest(__VLS_100));
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
            (__VLS_unwrap(t, {})("Add model rates to estimate costs.", "配置模型费率后即可估算成本。"));
            let __VLS_104;
            /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
            RouterLink;
            // @ts-ignore
            const __VLS_105 = __VLS_asFunctionalComponent1(__VLS_104, new __VLS_104({
                // @ts-ignore
                to: "/app/settings",
            }));
            const __VLS_106 = __VLS_105({
                to: "/app/settings",
            }, ...__VLS_functionalComponentArgsRest(__VLS_105));
            const { default: __VLS_109 } = __VLS_nonNull(__VLS_107.slots);
            (__VLS_unwrap(t, {})("Configure pricing →", "配置价格 →"));
            // @ts-ignore
            [t, t,];
            var __VLS_107;
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "panel-heading" },
        });
        /** @type {__VLS_StyleScopedClasses['panel-heading']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Recent activity", "最近活动"));
        let __VLS_110;
        /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
        RouterLink;
        // @ts-ignore
        const __VLS_111 = __VLS_asFunctionalComponent1(__VLS_110, new __VLS_110({
            // @ts-ignore
            to: "/app/traces",
        }));
        const __VLS_112 = __VLS_111({
            to: "/app/traces",
        }, ...__VLS_functionalComponentArgsRest(__VLS_111));
        const { default: __VLS_115 } = __VLS_nonNull(__VLS_113.slots);
        (__VLS_unwrap(t, {})("View traces", "查看调用"));
        // @ts-ignore
        [t, t,];
        var __VLS_113;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "table-wrap" },
        });
        /** @type {__VLS_StyleScopedClasses['table-wrap']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.table, __VLS_intrinsics.table)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.thead, __VLS_intrinsics.thead)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Time", "时间"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Object", "对象"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Type", "类型"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Model", "模型"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Cost", "成本"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Status", "状态"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.tbody, __VLS_intrinsics.tbody)({});
        const __VLS_116 = __VLS_tryAsConstant((recent.value));
        for (const [o] of __VLS_vFor(__VLS_nonNull(__VLS_116))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({
                key: (o.object_id),
            });
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(date, {})(o.started_at));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            if (o.trace_id) {
                let __VLS_117;
                /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
                RouterLink;
                // @ts-ignore
                const __VLS_118 = __VLS_asFunctionalComponent1(__VLS_117, new __VLS_117({
                    // @ts-ignore
                    to: ('/app/trace/' + o.trace_id),
                }));
                const __VLS_119 = __VLS_118({
                    to: ('/app/trace/' + o.trace_id),
                }, ...__VLS_functionalComponentArgsRest(__VLS_118));
                const { default: __VLS_122 } = __VLS_nonNull(__VLS_120.slots);
                (o.data.name || o.object_id);
                // @ts-ignore
                [t, t, t, t, t, t, date, recent,];
                var __VLS_120;
            }
            else {
                __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
                (o.data.name || o.object_id);
            }
            if (o.unmatched) {
                __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({
                    ...{ class: "amber" },
                });
                /** @type {__VLS_StyleScopedClasses['amber']} */ ;
                (__VLS_unwrap(t, {})("Unmatched parent", "父对象尚未到达"));
            }
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                ...{ class: "badge" },
            });
            /** @type {__VLS_StyleScopedClasses['badge']} */ ;
            (o.type);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (o.data.model || "—");
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (o.type === "observation" ? __VLS_unwrap(money, {})(o.cost) : "—");
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                ...{ class: ([
                        'badge',
                        o.data.status === 'error' ? 'danger' : 'success',
                    ]) },
            });
            /** @type {__VLS_StyleScopedClasses['badge']} */ ;
            (o.data.status || o.data.valueType);
            // @ts-ignore
            [t, money,];
        }
        if (!recent.value.length) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({
                colspan: "6",
                ...{ class: "empty-cell" },
            });
            /** @type {__VLS_StyleScopedClasses['empty-cell']} */ ;
            (__VLS_unwrap(t, {})("No events yet. Connect your application to get started.", "暂无事件，请接入应用开始采集。"));
            let __VLS_123;
            /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
            RouterLink;
            // @ts-ignore
            const __VLS_124 = __VLS_asFunctionalComponent1(__VLS_123, new __VLS_123({
                // @ts-ignore
                to: "/app/keys",
            }));
            const __VLS_125 = __VLS_124({
                to: "/app/keys",
            }, ...__VLS_functionalComponentArgsRest(__VLS_124));
            const { default: __VLS_128 } = __VLS_nonNull(__VLS_126.slots);
            (__VLS_unwrap(t, {})("Connect →", "开始接入 →"));
            // @ts-ignore
            [t, t, recent,];
            var __VLS_126;
        }
    }
    if (page.value === 'traces' && selected.value) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
            ...{ onSubmit: // @ts-ignore
                (...[$event]) => {
                    void $event;
                    if (!(page.value === 'traces' && selected.value))
                        throw 0;
                    tracePage.value = 0;
                    refresh();
                    ;
                    // @ts-ignore
                    [page, selected, tracePage,];
                } },
            ...{ class: "filters" },
        });
        /** @type {__VLS_StyleScopedClasses['filters']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "search-field" },
        });
        /** @type {__VLS_StyleScopedClasses['search-field']} */ ;
        let __VLS_129;
        /** @ts-ignore @type { | typeof __VLS_components.Search} */
        Search;
        // @ts-ignore
        const __VLS_130 = __VLS_asFunctionalComponent1(__VLS_129, new __VLS_129({
            // @ts-ignore
            size: (17),
        }));
        const __VLS_131 = __VLS_130({
            size: (17),
        }, ...__VLS_functionalComponentArgsRest(__VLS_130));
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            placeholder: (__VLS_unwrap(t, {})('Search trace name or ID…', '搜索调用名称或 ID…')),
        });
        (__VLS_unwrap(search, {}));
        __VLS_asFunctionalElement1(__VLS_intrinsics.select, __VLS_intrinsics.select)({
            value: (__VLS_unwrap(status, {})),
        });
        __VLS_asFunctionalElement1(__VLS_intrinsics.option, __VLS_intrinsics.option)({
            value: "",
        });
        (__VLS_unwrap(t, {})("All statuses", "所有状态"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.option, __VLS_intrinsics.option)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.option, __VLS_intrinsics.option)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.option, __VLS_intrinsics.option)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ class: "btn primary" },
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        /** @type {__VLS_StyleScopedClasses['primary']} */ ;
        (__VLS_unwrap(t, {})("Search", "搜索"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "muted" },
        });
        /** @type {__VLS_StyleScopedClasses['muted']} */ ;
        (__VLS_unwrap(n, {})(data.value.total || 0));
        (__VLS_unwrap(t, {})("traces", "条调用"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "table-wrap" },
        });
        /** @type {__VLS_StyleScopedClasses['table-wrap']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.table, __VLS_intrinsics.table)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.thead, __VLS_intrinsics.thead)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Started at", "开始时间"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Name", "名称"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Duration", "耗时"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("User / Customer", "用户 / 客户"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Status", "状态"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.tbody, __VLS_intrinsics.tbody)({});
        const __VLS_134 = __VLS_tryAsConstant((data.value.items || []));
        for (const [o] of __VLS_vFor(__VLS_nonNull(__VLS_134))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({
                key: (o.object_id),
            });
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(date, {})(o.started_at));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
            (o.data.name);
            __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({
                ...{ class: "mono" },
            });
            /** @type {__VLS_StyleScopedClasses['mono']} */ ;
            (o.object_id);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(n, {})(o.data.durationMs));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (o.data.userId || "—");
            (o.data.customerId || "—");
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                ...{ class: ([
                        'badge',
                        o.data.status === 'error' ? 'danger' : 'success',
                    ]) },
            });
            /** @type {__VLS_StyleScopedClasses['badge']} */ ;
            (o.data.status);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            let __VLS_135;
            /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
            RouterLink;
            // @ts-ignore
            const __VLS_136 = __VLS_asFunctionalComponent1(__VLS_135, new __VLS_135({
                // @ts-ignore
                to: ('/app/trace/' + o.trace_id),
            }));
            const __VLS_137 = __VLS_136({
                to: ('/app/trace/' + o.trace_id),
            }, ...__VLS_functionalComponentArgsRest(__VLS_136));
            const { default: __VLS_140 } = __VLS_nonNull(__VLS_138.slots);
            (__VLS_unwrap(t, {})("View", "查看"));
            // @ts-ignore
            [t, t, t, t, t, t, t, t, t, t, n, n, date, search, status, data, data,];
            var __VLS_138;
            // @ts-ignore
            [];
        }
        if (!data.value.items?.length) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({
                colspan: "6",
                ...{ class: "empty-cell" },
            });
            /** @type {__VLS_StyleScopedClasses['empty-cell']} */ ;
            (__VLS_unwrap(t, {})("No traces match these filters.", "没有符合筛选条件的调用。"));
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "pagination" },
        });
        /** @type {__VLS_StyleScopedClasses['pagination']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: // @ts-ignore
                (...[$event]) => {
                    void $event;
                    if (!(page.value === 'traces' && selected.value))
                        throw 0;
                    tracePage.value--;
                    refresh();
                    ;
                    // @ts-ignore
                    [t, tracePage, data,];
                } },
            ...{ class: "btn" },
            disabled: (tracePage.value === 0),
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
        (tracePage.value + 1);
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: // @ts-ignore
                (...[$event]) => {
                    void $event;
                    if (!(page.value === 'traces' && selected.value))
                        throw 0;
                    tracePage.value++;
                    refresh();
                    ;
                    // @ts-ignore
                    [tracePage, tracePage, tracePage,];
                } },
            ...{ class: "btn" },
            disabled: ((tracePage.value + 1) * 50 >= (data.value.total || 0)),
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
    }
    if (page.value === 'trace' && selected.value) {
        let __VLS_141;
        /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
        RouterLink;
        // @ts-ignore
        const __VLS_142 = __VLS_asFunctionalComponent1(__VLS_141, new __VLS_141({
            // @ts-ignore
            ...{ class: "back-link" }, to: "/app/traces",
        }));
        const __VLS_143 = __VLS_142({
            ...{ class: "back-link" },
            to: "/app/traces",
        }, ...__VLS_functionalComponentArgsRest(__VLS_142));
        /** @type {__VLS_StyleScopedClasses['back-link']} */ ;
        const { default: __VLS_146 } = __VLS_nonNull(__VLS_144.slots);
        let __VLS_147;
        /** @ts-ignore @type { | typeof __VLS_components.ArrowLeft} */
        ArrowLeft;
        // @ts-ignore
        const __VLS_148 = __VLS_asFunctionalComponent1(__VLS_147, new __VLS_147({
            // @ts-ignore
            size: (16),
        }));
        const __VLS_149 = __VLS_148({
            size: (16),
        }, ...__VLS_functionalComponentArgsRest(__VLS_148));
        (__VLS_unwrap(t, {})("All traces", "全部调用"));
        // @ts-ignore
        [t, page, selected, tracePage, data,];
        var __VLS_144;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "panel trace-title" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        /** @type {__VLS_StyleScopedClasses['trace-title']} */ ;
        let __VLS_152;
        /** @ts-ignore @type { | typeof __VLS_components.GitBranch} */
        GitBranch;
        // @ts-ignore
        const __VLS_153 = __VLS_asFunctionalComponent1(__VLS_152, new __VLS_152({
            // @ts-ignore
            size: (26),
        }));
        const __VLS_154 = __VLS_153({
            size: (26),
        }, ...__VLS_functionalComponentArgsRest(__VLS_153));
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (traceObjects.value.find((o) => o.type === "trace")?.data
            .name || __VLS_unwrap(t, {})("Awaiting trace parent", "等待父 Trace"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.code, __VLS_intrinsics.code)({});
        (route.value.params.detail);
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "badge" },
        });
        /** @type {__VLS_StyleScopedClasses['badge']} */ ;
        (traceObjects.value.length);
        (__VLS_unwrap(t, {})("objects", "个对象"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "panel-heading" },
        });
        /** @type {__VLS_StyleScopedClasses['panel-heading']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Execution waterfall", "执行瀑布图"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "muted" },
        });
        /** @type {__VLS_StyleScopedClasses['muted']} */ ;
        const __VLS_157 = __VLS_tryAsConstant((traceObjects.value.filter((o) => o.type !== 'score')));
        for (const [o, i] of __VLS_vFor(__VLS_nonNull(__VLS_157))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "waterfall" },
                key: (o.object_id),
            });
            /** @type {__VLS_StyleScopedClasses['waterfall']} */ ;
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                ...{ class: "step-number" },
            });
            /** @type {__VLS_StyleScopedClasses['step-number']} */ ;
            (Number(i) + 1);
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.b, __VLS_intrinsics.b)({});
            (o.data.name || o.data.model || o.object_id);
            __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
            (o.type);
            (o.data.observationType || o.data.status);
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "waterfall-track" },
            });
            /** @type {__VLS_StyleScopedClasses['waterfall-track']} */ ;
            __VLS_asFunctionalElement1(__VLS_intrinsics.i, __VLS_intrinsics.i)({
                ...{ style: ({
                        marginLeft: (100 * (new Date(o.started_at).getTime() - __VLS_unwrap(traceStart, {}))) /
                            __VLS_unwrap(traceDuration, {}) +
                            '%',
                        width: Math.max(0.2, (100 * (o.data.durationMs || 0)) / __VLS_unwrap(traceDuration, {})) + '%',
                        background: o.data.status === 'error'
                            ? '#e25555'
                            : Number(i) % 2
                                ? '#67c9b2'
                                : '#74adfc',
                    }) },
            });
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
            (__VLS_unwrap(n, {})(o.data.durationMs));
            // @ts-ignore
            [t, t, t, n, traceObjects, traceObjects, traceObjects, route, traceStart, traceDuration, traceDuration,];
        }
        if (!traceObjects.value.length) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
                ...{ class: "empty-cell" },
            });
            /** @type {__VLS_StyleScopedClasses['empty-cell']} */ ;
            (__VLS_unwrap(t, {})("This trace is not available.", "该调用不存在或已超过保留期。"));
        }
        const __VLS_158 = __VLS_tryAsConstant((traceObjects.value));
        for (const [o] of __VLS_vFor(__VLS_nonNull(__VLS_158))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
                key: (o.object_id),
                ...{ class: "panel detail-card" },
            });
            /** @type {__VLS_StyleScopedClasses['panel']} */ ;
            /** @type {__VLS_StyleScopedClasses['detail-card']} */ ;
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "panel-heading" },
            });
            /** @type {__VLS_StyleScopedClasses['panel-heading']} */ ;
            __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
            (o.data.name || o.data.model || o.type);
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                ...{ class: "badge" },
            });
            /** @type {__VLS_StyleScopedClasses['badge']} */ ;
            (o.revision);
            __VLS_asFunctionalElement1(__VLS_intrinsics.dl, __VLS_intrinsics.dl)({});
            const __VLS_159 = __VLS_tryAsConstant((o.data));
            for (const [value, key] of __VLS_vFor(__VLS_nonNull(__VLS_159))) {
                __VLS_asFunctionalElement1(__VLS_intrinsics.template)({
                    key: (key),
                });
                __VLS_asFunctionalElement1(__VLS_intrinsics.dt, __VLS_intrinsics.dt)({});
                (key);
                __VLS_asFunctionalElement1(__VLS_intrinsics.dd, __VLS_intrinsics.dd)({});
                if (typeof value === 'object') {
                    __VLS_asFunctionalElement1(__VLS_intrinsics.code, __VLS_intrinsics.code)({});
                    (JSON.stringify(value));
                }
                else {
                    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
                    (value ?? __VLS_unwrap(t, {})("Unknown", "未知"));
                }
                // @ts-ignore
                [t, t, traceObjects, traceObjects,];
            }
            __VLS_asFunctionalElement1(__VLS_intrinsics.dt, __VLS_intrinsics.dt)({});
            (__VLS_unwrap(t, {})("Estimated cost", "估算成本"));
            __VLS_asFunctionalElement1(__VLS_intrinsics.dd, __VLS_intrinsics.dd)({});
            (o.type === "observation" ? __VLS_unwrap(money, {})(o.cost) : "—");
            // @ts-ignore
            [t, money,];
        }
    }
    if (page.value === 'analytics' && selected.value) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "tabs" },
        });
        /** @type {__VLS_StyleScopedClasses['tabs']} */ ;
        const __VLS_160 = __VLS_tryAsConstant((['customerId', 'userId', 'feature']));
        for (const [d] of __VLS_vFor(__VLS_nonNull(__VLS_160))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
                ...{ onClick: // @ts-ignore
                    (...[$event]) => {
                        void $event;
                        if (!(page.value === 'analytics' && selected.value))
                            throw 0;
                        dimension.value = d;
                        refresh();
                        ;
                        // @ts-ignore
                        [page, selected, dimension,];
                    } },
                ...{ class: ({ active: dimension.value === d }) },
            });
            /** @type {__VLS_StyleScopedClasses['active']} */ ;
            (d === "customerId"
                ? __VLS_unwrap(t, {})("Customers", "客户")
                : d === "userId"
                    ? __VLS_unwrap(t, {})("Users", "用户")
                    : __VLS_unwrap(t, {})("Features", "功能"));
            // @ts-ignore
            [t, t, t, dimension,];
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel table-wrap" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        /** @type {__VLS_StyleScopedClasses['table-wrap']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.table, __VLS_intrinsics.table)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.thead, __VLS_intrinsics.thead)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Identifier", "标识"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Objects", "对象数"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Known tokens", "已知 Token"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Estimated cost", "估算成本"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Unknown costs", "未知成本调用"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.tbody, __VLS_intrinsics.tbody)({});
        const __VLS_161 = __VLS_tryAsConstant((Array.isArray(data.value) ? data.value : []));
        for (const [r] of __VLS_vFor(__VLS_nonNull(__VLS_161))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (r.dimension || __VLS_unwrap(t, {})("Not provided", "未提供"));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(n, {})(r.objects));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(n, {})(r.tokens));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(money, {})(r.cost));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(n, {})(r.unknown_costs));
            // @ts-ignore
            [t, t, t, t, t, t, n, n, n, money, data, data,];
        }
        if (!data.value.length) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({
                colspan: "5",
                ...{ class: "empty-cell" },
            });
            /** @type {__VLS_StyleScopedClasses['empty-cell']} */ ;
            (__VLS_unwrap(t, {})("Submit userId, customerId, or feature with your events to explore usage.", "在事件中提交 userId、customerId 或 feature 后即可分析用量。"));
        }
    }
    if (page.value === 'keys' && selected.value) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel integration-banner" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        /** @type {__VLS_StyleScopedClasses['integration-banner']} */ ;
        let __VLS_162;
        /** @ts-ignore @type { | typeof __VLS_components.CodeIcon} */
        CodeIcon;
        // @ts-ignore
        const __VLS_163 = __VLS_asFunctionalComponent1(__VLS_162, new __VLS_162({
        // @ts-ignore
        }));
        const __VLS_164 = __VLS_163({}, ...__VLS_functionalComponentArgsRest(__VLS_163));
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Connect your application", "接入你的应用"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
        (__VLS_unwrap(t, {})("Project-scoped, write-only keys. Full keys appear once and are never stored in your browser.", "密钥仅限当前项目写入，完整值只展示一次，不保存到浏览器。"));
        let __VLS_167;
        /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
        RouterLink;
        // @ts-ignore
        const __VLS_168 = __VLS_asFunctionalComponent1(__VLS_167, new __VLS_167({
            // @ts-ignore
            ...{ class: "btn" }, to: "/docs",
        }));
        const __VLS_169 = __VLS_168({
            ...{ class: "btn" },
            to: "/docs",
        }, ...__VLS_functionalComponentArgsRest(__VLS_168));
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        const { default: __VLS_172 } = __VLS_nonNull(__VLS_170.slots);
        (__VLS_unwrap(t, {})("Quickstart", "快速开始"));
        let __VLS_173;
        /** @ts-ignore @type { | typeof __VLS_components.ArrowUpRight} */
        ArrowUpRight;
        // @ts-ignore
        const __VLS_174 = __VLS_asFunctionalComponent1(__VLS_173, new __VLS_173({
            // @ts-ignore
            size: (15),
        }));
        const __VLS_175 = __VLS_174({
            size: (15),
        }, ...__VLS_functionalComponentArgsRest(__VLS_174));
        // @ts-ignore
        [t, t, t, t, page, selected, data,];
        var __VLS_170;
        if (createdKey.value) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
                ...{ class: "notice success" },
            });
            /** @type {__VLS_StyleScopedClasses['notice']} */ ;
            /** @type {__VLS_StyleScopedClasses['success']} */ ;
            __VLS_asFunctionalElement1(__VLS_intrinsics.b, __VLS_intrinsics.b)({});
            (__VLS_unwrap(t, {})("Copy your key now. It will not be shown again.", "请立即复制密钥，之后无法再次查看。"));
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "key-reveal" },
            });
            /** @type {__VLS_StyleScopedClasses['key-reveal']} */ ;
            __VLS_asFunctionalElement1(__VLS_intrinsics.code, __VLS_intrinsics.code)({});
            (createdKey.value);
            __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
                ...{ onClick: (copyKey) },
                ...{ class: "btn" },
            });
            /** @type {__VLS_StyleScopedClasses['btn']} */ ;
            const __VLS_178 = (copied.value ? __VLS_unwrap(Check, {}) : __VLS_unwrap(Copy, {}));
            // @ts-ignore
            const __VLS_179 = __VLS_asFunctionalComponent1(__VLS_178, new __VLS_178({
                // @ts-ignore
                size: (16),
            }));
            const __VLS_180 = __VLS_179({
                size: (16),
            }, ...__VLS_functionalComponentArgsRest(__VLS_179));
            __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
                ...{ onClick: // @ts-ignore
                    (...[$event]) => {
                        void $event;
                        if (!(page.value === 'keys' && selected.value))
                            throw 0;
                        if (!(createdKey.value))
                            throw 0;
                        return (createdKey.value = '');
                        // @ts-ignore
                        [t, createdKey, createdKey, createdKey, copied, Check, Copy,];
                    } },
                ...{ class: "btn" },
            });
            /** @type {__VLS_StyleScopedClasses['btn']} */ ;
            (__VLS_unwrap(t, {})("Dismiss", "关闭"));
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "panel-heading" },
        });
        /** @type {__VLS_StyleScopedClasses['panel-heading']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("API keys", "API 密钥"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
            ...{ onSubmit: (createKey) },
            ...{ class: "inline-form" },
        });
        /** @type {__VLS_StyleScopedClasses['inline-form']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            required: true,
            maxlength: "128",
            'aria-label': (__VLS_unwrap(t, {})('Key name', '密钥名称')),
        });
        (__VLS_unwrap(keyName, {}));
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ class: "btn primary" },
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        /** @type {__VLS_StyleScopedClasses['primary']} */ ;
        let __VLS_183;
        /** @ts-ignore @type { | typeof __VLS_components.Plus} */
        Plus;
        // @ts-ignore
        const __VLS_184 = __VLS_asFunctionalComponent1(__VLS_183, new __VLS_183({
            // @ts-ignore
            size: (16),
        }));
        const __VLS_185 = __VLS_184({
            size: (16),
        }, ...__VLS_functionalComponentArgsRest(__VLS_184));
        (__VLS_unwrap(t, {})("Create key", "创建密钥"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "table-wrap" },
        });
        /** @type {__VLS_StyleScopedClasses['table-wrap']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.table, __VLS_intrinsics.table)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.thead, __VLS_intrinsics.thead)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Name", "名称"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Prefix", "前缀"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Created", "创建时间"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Status", "状态"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.tbody, __VLS_intrinsics.tbody)({});
        const __VLS_188 = __VLS_tryAsConstant((Array.isArray(data.value) ? data.value : []));
        for (const [k] of __VLS_vFor(__VLS_nonNull(__VLS_188))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({
                key: (k.id),
            });
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (k.name);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.code, __VLS_intrinsics.code)({});
            (k.prefix);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(date, {})(k.created_at));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                ...{ class: (['badge', k.revoked_at ? '' : 'success']) },
            });
            /** @type {__VLS_StyleScopedClasses['badge']} */ ;
            (k.revoked_at
                ? __VLS_unwrap(t, {})("Revoked", "已撤销")
                : __VLS_unwrap(t, {})("Active", "有效"));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            if (!k.revoked_at) {
                __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
                    ...{ onClick: // @ts-ignore
                        (...[$event]) => {
                            void $event;
                            if (!(page.value === 'keys' && selected.value))
                                throw 0;
                            if (!(!k.revoked_at))
                                throw 0;
                            return (revoke(k.id));
                            // @ts-ignore
                            [t, t, t, t, t, t, t, t, t, t, date, data, data, keyName,];
                        } },
                    ...{ class: "text-button danger-text" },
                });
                /** @type {__VLS_StyleScopedClasses['text-button']} */ ;
                /** @type {__VLS_StyleScopedClasses['danger-text']} */ ;
                (__VLS_unwrap(t, {})("Revoke", "撤销"));
            }
            // @ts-ignore
            [t,];
        }
        if (!data.value.length) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({
                colspan: "5",
                ...{ class: "empty-cell" },
            });
            /** @type {__VLS_StyleScopedClasses['empty-cell']} */ ;
            (__VLS_unwrap(t, {})("No keys yet. Create one to start sending events.", "暂无密钥，创建后即可上报事件。"));
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "panel-foot" },
        });
        /** @type {__VLS_StyleScopedClasses['panel-foot']} */ ;
        (__VLS_unwrap(t, {})("To rotate: create a new key, migrate your application, then revoke the previous key. Revocation takes effect immediately.", "轮换方式：创建新密钥、迁移应用，再撤销旧密钥。撤销立即生效。"));
    }
    if (page.value === 'alerts' && selected.value) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel form-panel" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        /** @type {__VLS_StyleScopedClasses['form-panel']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Create alert rule", "创建告警规则"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
        (__VLS_unwrap(t, {})("Evaluated every minute against the last hour. Email notifications have a one-hour cooldown.", "每分钟检查最近一小时数据，邮件通知冷却时间为一小时。"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
            ...{ onSubmit: // @ts-ignore
                (...[$event]) => {
                    void $event;
                    if (!(page.value === 'alerts' && selected.value))
                        throw 0;
                    return (run(async () => {
                        await __VLS_unwrap(api, {})(__VLS_unwrap(path, {})('/alerts'), alert.value);
                        alert.value.name = '';
                        await refresh();
                    }));
                    // @ts-ignore
                    [t, t, t, t, page, selected, data, api, path, alert, alert,];
                } },
            ...{ class: "form-grid" },
        });
        /** @type {__VLS_StyleScopedClasses['form-grid']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        (__VLS_unwrap(t, {})("Rule name", "规则名称"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            required: true,
            maxlength: "128",
        });
        (alert.value.name);
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        (__VLS_unwrap(t, {})("Metric", "指标"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.select, __VLS_intrinsics.select)({
            value: (alert.value.metric),
        });
        __VLS_asFunctionalElement1(__VLS_intrinsics.option, __VLS_intrinsics.option)({
            value: "error_count",
        });
        (__VLS_unwrap(t, {})("Error count", "错误数量"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.option, __VLS_intrinsics.option)({
            value: "cost_usd",
        });
        (__VLS_unwrap(t, {})("Known cost (USD)", "已知费用 (USD)"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.option, __VLS_intrinsics.option)({
            value: "latency_ms",
        });
        (__VLS_unwrap(t, {})("Maximum latency (ms)", "最大延迟 (ms)"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        (__VLS_unwrap(t, {})("Threshold ≥", "阈值 ≥"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            type: "number",
            min: "0.000001",
            step: "any",
            required: true,
        });
        (alert.value.threshold);
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ class: "btn primary" },
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        /** @type {__VLS_StyleScopedClasses['primary']} */ ;
        (__VLS_unwrap(t, {})("Create rule", "创建规则"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel table-wrap" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        /** @type {__VLS_StyleScopedClasses['table-wrap']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.table, __VLS_intrinsics.table)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.thead, __VLS_intrinsics.thead)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Rule", "规则"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Metric", "指标"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Threshold", "阈值"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Last triggered", "最近触发"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.tbody, __VLS_intrinsics.tbody)({});
        const __VLS_189 = __VLS_tryAsConstant((data.value.rules || []));
        for (const [r] of __VLS_vFor(__VLS_nonNull(__VLS_189))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (r.name);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (r.metric);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (r.threshold);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(date, {})(r.last_triggered_at));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
                ...{ onClick: // @ts-ignore
                    (...[$event]) => {
                        void $event;
                        if (!(page.value === 'alerts' && selected.value))
                            throw 0;
                        return (run(async () => {
                            await __VLS_unwrap(api, {})(__VLS_unwrap(path, {})('/alerts/' + r.id), {
                                enabled: !r.enabled,
                            });
                            await refresh();
                        }));
                        // @ts-ignore
                        [t, t, t, t, t, t, t, t, t, t, t, date, data, api, path, alert, alert, alert,];
                    } },
                ...{ class: "btn" },
            });
            /** @type {__VLS_StyleScopedClasses['btn']} */ ;
            (r.enabled ? __VLS_unwrap(t, {})("Disable", "停用") : __VLS_unwrap(t, {})("Enable", "启用"));
            // @ts-ignore
            [t, t,];
        }
        if (!data.value.rules?.length) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
                ...{ class: "empty-cell" },
            });
            /** @type {__VLS_StyleScopedClasses['empty-cell']} */ ;
            (__VLS_unwrap(t, {})("No rules configured.", "尚未配置规则。"));
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "panel-heading" },
        });
        /** @type {__VLS_StyleScopedClasses['panel-heading']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Recent notifications", "最近告警"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.table, __VLS_intrinsics.table)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.tbody, __VLS_intrinsics.tbody)({});
        const __VLS_190 = __VLS_tryAsConstant((data.value.events || []));
        for (const [e] of __VLS_vFor(__VLS_nonNull(__VLS_190))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (e.name);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (e.value);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(date, {})(e.created_at));
            // @ts-ignore
            [t, t, date, data, data,];
        }
        if (!data.value.events?.length) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({
                ...{ class: "empty-cell" },
            });
            /** @type {__VLS_StyleScopedClasses['empty-cell']} */ ;
            (__VLS_unwrap(t, {})("No alerts triggered.", "暂无触发记录。"));
        }
    }
    if (page.value === 'billing') {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "notice" },
        });
        /** @type {__VLS_StyleScopedClasses['notice']} */ ;
        (__VLS_unwrap(t, {})("Platform units and model provider costs are separate. Payments require an operator-configured hosted checkout.", "平台 units 与模型供应商费用独立核算，支付需运营方配置托管收银台。"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "plans" },
        });
        /** @type {__VLS_StyleScopedClasses['plans']} */ ;
        const __VLS_191 = __VLS_tryAsConstant((['Free', 'Core', 'Pro']));
        for (const [plan, i] of __VLS_vFor(__VLS_nonNull(__VLS_191))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.article, __VLS_intrinsics.article)({
                ...{ class: (['panel', { featured: data.value.workspace?.plan === plan }]) },
            });
            /** @type {__VLS_StyleScopedClasses['featured']} */ ;
            /** @type {__VLS_StyleScopedClasses['panel']} */ ;
            __VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
            (plan);
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "price" },
            });
            /** @type {__VLS_StyleScopedClasses['price']} */ ;
            ([0, 29, 199][i]);
            __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
            (__VLS_unwrap(t, {})("month", "月"));
            __VLS_asFunctionalElement1(__VLS_intrinsics.ul, __VLS_intrinsics.ul)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
            let __VLS_192;
            /** @ts-ignore @type { | typeof __VLS_components.Check} */
            Check;
            // @ts-ignore
            const __VLS_193 = __VLS_asFunctionalComponent1(__VLS_192, new __VLS_192({
                // @ts-ignore
                size: (16),
            }));
            const __VLS_194 = __VLS_193({
                size: (16),
            }, ...__VLS_functionalComponentArgsRest(__VLS_193));
            (i === 0 ? "50,000" : "100,000");
            __VLS_asFunctionalElement1(__VLS_intrinsics.li, __VLS_intrinsics.li)({});
            let __VLS_197;
            /** @ts-ignore @type { | typeof __VLS_components.Check} */
            Check;
            // @ts-ignore
            const __VLS_198 = __VLS_asFunctionalComponent1(__VLS_197, new __VLS_197({
                // @ts-ignore
                size: (16),
            }));
            const __VLS_199 = __VLS_198({
                size: (16),
            }, ...__VLS_functionalComponentArgsRest(__VLS_198));
            ([
                __VLS_unwrap(t, {})("30 days", "30 天"),
                __VLS_unwrap(t, {})("90 days", "90 天"),
                __VLS_unwrap(t, {})("3 years", "3 年"),
            ][i]);
            (__VLS_unwrap(t, {})("data access", "数据访问"));
            __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
                ...{ onClick: // @ts-ignore
                    (...[$event]) => {
                        void $event;
                        if (!(page.value === 'billing'))
                            throw 0;
                        return (run(async () => {
                            const r = await __VLS_unwrap(api, {})('/billing/checkout', { plan });
                            __VLS_unwrap(navigate, {})(r.url);
                        }));
                        // @ts-ignore
                        [t, t, t, t, t, t, t, page, data, data, api, navigate,];
                    } },
                ...{ class: "btn full" },
                disabled: (data.value.workspace?.plan === plan || plan === 'Free'),
            });
            /** @type {__VLS_StyleScopedClasses['btn']} */ ;
            /** @type {__VLS_StyleScopedClasses['full']} */ ;
            (data.value.workspace?.plan === plan
                ? __VLS_unwrap(t, {})("Current plan", "当前套餐")
                : __VLS_unwrap(t, {})("Choose ", "选择 ") + plan);
            // @ts-ignore
            [t, t, data, data,];
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel form-panel" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        /** @type {__VLS_StyleScopedClasses['form-panel']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Monthly usage", "当月用量"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "billing-usage" },
        });
        /** @type {__VLS_StyleScopedClasses['billing-usage']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
        (__VLS_unwrap(n, {})(data.value.workspace?.used_units));
        __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
        (__VLS_unwrap(n, {})(data.value.included));
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
        (__VLS_unwrap(t, {})("Overage accrued", "已产生超额费"));
        (__VLS_unwrap(money, {})(data.value.overage));
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
            ...{ class: "muted" },
        });
        /** @type {__VLS_StyleScopedClasses['muted']} */ ;
        (__VLS_unwrap(date, {})(data.value.workspace?.period_start));
        (__VLS_unwrap(date, {})(data.value.workspace?.period_end));
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Overage policy", "超额策略"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
        (__VLS_unwrap(t, {})("New objects stop by default. Core and Pro may opt into progressive overage starting at $8 per 100,000 units. Existing object updates do not add units.", "默认停止接收新增对象。Core 和 Pro 可开启分段超额计费，起价 $8 / 100,000 units。更新已有对象不增加 units。"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
            ...{ onSubmit: (saveOverage) },
        });
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({
            ...{ class: "check-label" },
        });
        /** @type {__VLS_StyleScopedClasses['check-label']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            type: "checkbox",
            disabled: (data.value.workspace?.plan === 'Free'),
        });
        (billing.value.enabled);
        (__VLS_unwrap(t, {})("Enable automatic overage", "开启自动超额计费"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({
            ...{ class: "narrow" },
        });
        /** @type {__VLS_StyleScopedClasses['narrow']} */ ;
        (__VLS_unwrap(t, {})("Monthly overage cap (USD)", "月度超额费用上限 (USD)"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            type: "number",
            min: "0",
            step: "0.01",
            required: true,
        });
        (billing.value.cap);
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({
            ...{ class: "check-label" },
        });
        /** @type {__VLS_StyleScopedClasses['check-label']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            type: "checkbox",
            required: (billing.value.enabled),
        });
        (billing.value.consent);
        (__VLS_unwrap(t, {})("I authorize usage-based charges within this cap.", "我授权在此上限内按实际使用量收费。"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ class: "btn primary" },
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        /** @type {__VLS_StyleScopedClasses['primary']} */ ;
        (__VLS_unwrap(t, {})("Verify & save", "验证并保存"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "panel-heading" },
        });
        /** @type {__VLS_StyleScopedClasses['panel-heading']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Billing history", "账单记录"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.table, __VLS_intrinsics.table)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.tbody, __VLS_intrinsics.tbody)({});
        const __VLS_202 = __VLS_tryAsConstant((data.value.payments || []));
        for (const [p] of __VLS_vFor(__VLS_nonNull(__VLS_202))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (p.id);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (p.status);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (p.amount);
            (p.currency);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(date, {})(p.created_at));
            // @ts-ignore
            [t, t, t, t, t, t, t, t, t, n, n, date, date, date, money, data, data, data, data, data, data, data, billing, billing, billing, billing,];
        }
        if (!data.value.payments?.length) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({
                ...{ class: "empty-cell" },
            });
            /** @type {__VLS_StyleScopedClasses['empty-cell']} */ ;
            (__VLS_unwrap(t, {})("No payments recorded.", "暂无支付记录。"));
        }
    }
    if (page.value === 'settings' && selected.value) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel form-panel" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        /** @type {__VLS_StyleScopedClasses['form-panel']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Versioned model pricing", "模型价格版本"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
        (__VLS_unwrap(t, {})("Rates are USD per million tokens. Unknown prices stay unknown. New versions apply when processing new events or revisions; existing cost evidence is preserved.", "费率为每百万 Token 的美元价格。缺失价格保持未知，新版本在处理新事件或新修订时使用，保留已有成本证据。"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
            ...{ onSubmit: // @ts-ignore
                (...[$event]) => {
                    void $event;
                    if (!(page.value === 'settings' && selected.value))
                        throw 0;
                    return (run(async () => {
                        await __VLS_unwrap(api, {})(__VLS_unwrap(path, {})('/prices'), {
                            ...price.value,
                            effectiveAt: new Date(price.value.effectiveAt + 'Z').toISOString(),
                        });
                        await refresh();
                    }));
                    // @ts-ignore
                    [t, t, t, page, selected, data, api, path, price, price,];
                } },
            ...{ class: "form-grid" },
        });
        /** @type {__VLS_StyleScopedClasses['form-grid']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            required: true,
            maxlength: "64",
        });
        (price.value.provider);
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            required: true,
            maxlength: "256",
        });
        (price.value.model);
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        (__VLS_unwrap(t, {})("Effective at (UTC)", "生效时间 (UTC)"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            type: "datetime-local",
            required: true,
        });
        (price.value.effectiveAt);
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        (__VLS_unwrap(t, {})("Input / 1M", "输入 / 百万"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            type: "number",
            min: "0",
            step: "any",
            required: true,
        });
        (price.value.inputRate);
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        (__VLS_unwrap(t, {})("Output / 1M", "输出 / 百万"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            type: "number",
            min: "0",
            step: "any",
            required: true,
        });
        (price.value.outputRate);
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        (__VLS_unwrap(t, {})("Cached input / 1M", "缓存输入 / 百万"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            type: "number",
            min: "0",
            step: "any",
            required: true,
        });
        (price.value.cachedRate);
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ class: "btn primary" },
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        /** @type {__VLS_StyleScopedClasses['primary']} */ ;
        (__VLS_unwrap(t, {})("Add price version", "添加价格版本"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel table-wrap" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        /** @type {__VLS_StyleScopedClasses['table-wrap']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.table, __VLS_intrinsics.table)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.thead, __VLS_intrinsics.thead)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Input", "输入"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Output", "输出"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Cached", "缓存"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Effective (UTC)", "生效时间 (UTC)"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.tbody, __VLS_intrinsics.tbody)({});
        const __VLS_203 = __VLS_tryAsConstant((Array.isArray(data.value) ? data.value : []));
        for (const [p] of __VLS_vFor(__VLS_nonNull(__VLS_203))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (p.provider);
            (p.model);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(money, {})(p.input_rate));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(money, {})(p.output_rate));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(money, {})(p.cached_rate));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(date, {})(p.effective_at));
            // @ts-ignore
            [t, t, t, t, t, t, t, t, t, date, money, money, money, data, data, price, price, price, price, price, price,];
        }
        if (!data.value.length) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({
                colspan: "5",
                ...{ class: "empty-cell" },
            });
            /** @type {__VLS_StyleScopedClasses['empty-cell']} */ ;
            (__VLS_unwrap(t, {})("No price versions. Model costs will be shown as unknown.", "未配置价格版本，模型成本将显示为未知。"));
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel form-panel danger-zone" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        /** @type {__VLS_StyleScopedClasses['form-panel']} */ ;
        /** @type {__VLS_StyleScopedClasses['danger-zone']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Delete project", "删除项目"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
        (__VLS_unwrap(t, {})("Keys are revoked immediately. Analytics detail is removed asynchronously; billing identities and audit records remain. Type the project name to confirm.", "密钥立即撤销，分析明细异步移除；计费身份与审计记录保留。输入项目名称确认。"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
            ...{ onSubmit: (deleteProject) },
            ...{ class: "inline-form" },
        });
        /** @type {__VLS_StyleScopedClasses['inline-form']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            placeholder: (project.value?.name),
            required: true,
        });
        (deleteConfirm.value);
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ class: "btn danger-button" },
            disabled: (deleteConfirm.value !== project.value?.name),
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        /** @type {__VLS_StyleScopedClasses['danger-button']} */ ;
        (__VLS_unwrap(t, {})("Verify & delete", "验证并删除"));
    }
    if (page.value === 'account') {
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel form-panel" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        /** @type {__VLS_StyleScopedClasses['form-panel']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
        (__VLS_unwrap(t, {})("Account details", "账号信息"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.dl, __VLS_intrinsics.dl)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.dt, __VLS_intrinsics.dt)({});
        (__VLS_unwrap(t, {})("Email", "邮箱"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.dd, __VLS_intrinsics.dd)({});
        (session.value?.email);
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "badge success" },
        });
        /** @type {__VLS_StyleScopedClasses['badge']} */ ;
        /** @type {__VLS_StyleScopedClasses['success']} */ ;
        (__VLS_unwrap(t, {})("Verified", "已验证"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.dt, __VLS_intrinsics.dt)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.dd, __VLS_intrinsics.dd)({});
        (session.value?.githubConnected
            ? __VLS_unwrap(t, {})("Connected", "已绑定")
            : __VLS_unwrap(t, {})("Not connected", "未绑定"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.dt, __VLS_intrinsics.dt)({});
        (__VLS_unwrap(t, {})("Session", "会话"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.dd, __VLS_intrinsics.dd)({});
        (__VLS_unwrap(t, {})("12 hours · HttpOnly cookie · revocable", "12 小时 · HttpOnly Cookie · 可撤销"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "inline-form" },
        });
        /** @type {__VLS_StyleScopedClasses['inline-form']} */ ;
        let __VLS_204;
        /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
        RouterLink;
        // @ts-ignore
        const __VLS_205 = __VLS_asFunctionalComponent1(__VLS_204, new __VLS_204({
            // @ts-ignore
            ...{ class: "btn" }, to: "/forgot-password",
        }));
        const __VLS_206 = __VLS_205({
            ...{ class: "btn" },
            to: "/forgot-password",
        }, ...__VLS_functionalComponentArgsRest(__VLS_205));
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        const { default: __VLS_209 } = __VLS_nonNull(__VLS_207.slots);
        (__VLS_unwrap(t, {})("Reset password", "重置密码"));
        // @ts-ignore
        [t, t, t, t, t, t, t, t, t, t, t, t, session, session, page, project, project, data, deleteConfirm, deleteConfirm,];
        var __VLS_207;
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: // @ts-ignore
                (...[$event]) => {
                    void $event;
                    __VLS_withDotValue(session, {});
                    if (!(page.value === 'account'))
                        throw 0;
                    return (verify(session.value?.githubConnected ? 'github.unlink' : 'github.link', 'github', async (proof) => {
                        const r = await __VLS_unwrap(api, {})('/account/github/' +
                            (session.value?.githubConnected ? 'unlink' : 'link'), proof);
                        if (r.url)
                            __VLS_unwrap(navigate, {})(r.url);
                    }));
                    // @ts-ignore
                    [session, session, api, navigate,];
                } },
            ...{ class: "btn" },
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        (session.value?.githubConnected
            ? __VLS_unwrap(t, {})("Unlink GitHub", "解绑 GitHub")
            : __VLS_unwrap(t, {})("Link GitHub", "绑定 GitHub"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: // @ts-ignore
                (...[$event]) => {
                    void $event;
                    if (!(page.value === 'account'))
                        throw 0;
                    return (run(logout));
                    // @ts-ignore
                    [t, t, session,];
                } },
            ...{ class: "btn" },
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        (__VLS_unwrap(t, {})("Sign out", "退出登录"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
            ...{ class: "muted" },
        });
        /** @type {__VLS_StyleScopedClasses['muted']} */ ;
        (__VLS_unwrap(t, {})("Resetting your password revokes all existing sessions. Email changes require verification of both addresses.", "重置密码将撤销所有已有会话。修改邮箱需要同时验证旧邮箱和新邮箱。"));
    }
    if (page.value === 'workspace') {
        const __VLS_210 = WorkspaceOverview;
        // @ts-ignore
        const __VLS_211 = __VLS_asFunctionalComponent1(__VLS_210, new __VLS_210({
            // @ts-ignore
            ...{ 'onSelect': {} },
        }));
        const __VLS_212 = __VLS_211({
            ...{ 'onSelect': {} },
        }, ...__VLS_functionalComponentArgsRest(__VLS_211));
        let __VLS_215;
        const __VLS_216 = {
            /** @type {typeof __VLS_215.select} */
            onSelect: // @ts-ignore
            (...[$event]) => {
                void $event;
                if (!(page.value === 'workspace'))
                    throw 0;
                selected.value = $event;
                router.value.push('/app/overview');
                ;
                // @ts-ignore
                [t, t, page, selected, router,];
            },
        };
        void __VLS_216;
        var __VLS_213;
        var __VLS_214;
    }
    if (page.value === 'settings' && project.value) {
        const __VLS_217 = ProjectInfoEditor;
        // @ts-ignore
        const __VLS_218 = __VLS_asFunctionalComponent1(__VLS_217, new __VLS_217({
            // @ts-ignore
            ...{ 'onSaved': {} }, project: (project.value),
        }));
        const __VLS_219 = __VLS_218({
            ...{ 'onSaved': {} },
            project: (project.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_218));
        let __VLS_222;
        const __VLS_223 = {
            /** @type {typeof __VLS_222.saved} */
            onSaved: (refresh),
        };
        void __VLS_223;
        var __VLS_220;
        var __VLS_221;
    }
    if (page.value === 'keys' && selected.value) {
        const __VLS_224 = BatchHistory;
        // @ts-ignore
        const __VLS_225 = __VLS_asFunctionalComponent1(__VLS_224, new __VLS_224({
            // @ts-ignore
            project: (selected.value),
        }));
        const __VLS_226 = __VLS_225({
            project: (selected.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_225));
    }
    if (page.value === 'account') {
        const __VLS_229 = AccountSecurity;
        // @ts-ignore
        const __VLS_230 = __VLS_asFunctionalComponent1(__VLS_229, new __VLS_229({
        // @ts-ignore
        }));
        const __VLS_231 = __VLS_230({}, ...__VLS_functionalComponentArgsRest(__VLS_230));
    }
    if (page.value === 'audit') {
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            ...{ class: "panel table-wrap" },
        });
        /** @type {__VLS_StyleScopedClasses['panel']} */ ;
        /** @type {__VLS_StyleScopedClasses['table-wrap']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.table, __VLS_intrinsics.table)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.thead, __VLS_intrinsics.thead)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Time (UTC)", "时间 (UTC)"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Action", "操作"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Target", "目标"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Result", "结果"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
        (__VLS_unwrap(t, {})("Request ID", "请求 ID"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.tbody, __VLS_intrinsics.tbody)({});
        const __VLS_234 = __VLS_tryAsConstant((Array.isArray(data.value) ? data.value : []));
        for (const [a] of __VLS_vFor(__VLS_nonNull(__VLS_234))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (__VLS_unwrap(date, {})(a.created_at));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (a.action);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({
                ...{ class: "mono" },
            });
            /** @type {__VLS_StyleScopedClasses['mono']} */ ;
            (a.target);
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                ...{ class: (['badge', a.success ? 'success' : 'danger']) },
            });
            /** @type {__VLS_StyleScopedClasses['badge']} */ ;
            (a.success ? __VLS_unwrap(t, {})("Success", "成功") : __VLS_unwrap(t, {})("Failed", "失败"));
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
            (a.ip || "—");
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({
                ...{ class: "mono" },
            });
            /** @type {__VLS_StyleScopedClasses['mono']} */ ;
            (a.request_id || "—");
            // @ts-ignore
            [t, t, t, t, t, t, t, page, page, page, page, selected, selected, date, project, project, data, data,];
        }
        if (!data.value.length) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
            __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({
                colspan: "6",
                ...{ class: "empty-cell" },
            });
            /** @type {__VLS_StyleScopedClasses['empty-cell']} */ ;
            (__VLS_unwrap(t, {})("No audit events.", "暂无审计记录。"));
        }
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "console-footer" },
    });
    /** @type {__VLS_StyleScopedClasses['console-footer']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
    (__VLS_unwrap(t, {})("Model cost is an estimate. Missing usage is never treated as zero.", "模型成本为估算值，缺失用量不会按零处理。"));
    if (showCreate.value) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "modal-backdrop" },
        });
        /** @type {__VLS_StyleScopedClasses['modal-backdrop']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            role: "dialog",
            'aria-modal': "true",
            ...{ class: "modal" },
            'aria-labelledby': "create-title",
        });
        /** @type {__VLS_StyleScopedClasses['modal']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: // @ts-ignore
                (...[$event]) => {
                    void $event;
                    if (!(showCreate.value))
                        throw 0;
                    return (showCreate.value = false);
                    // @ts-ignore
                    [t, t, showCreate, showCreate, data,];
                } },
            ...{ class: "modal-close icon-button" },
        });
        /** @type {__VLS_StyleScopedClasses['modal-close']} */ ;
        /** @type {__VLS_StyleScopedClasses['icon-button']} */ ;
        let __VLS_235;
        /** @ts-ignore @type { | typeof __VLS_components.X} */
        X;
        // @ts-ignore
        const __VLS_236 = __VLS_asFunctionalComponent1(__VLS_235, new __VLS_235({
            // @ts-ignore
            size: (20),
        }));
        const __VLS_237 = __VLS_236({
            size: (20),
        }, ...__VLS_functionalComponentArgsRest(__VLS_236));
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "modal-icon" },
        });
        /** @type {__VLS_StyleScopedClasses['modal-icon']} */ ;
        let __VLS_240;
        /** @ts-ignore @type { | typeof __VLS_components.FolderKanban} */
        FolderKanban;
        // @ts-ignore
        const __VLS_241 = __VLS_asFunctionalComponent1(__VLS_240, new __VLS_240({
            // @ts-ignore
            size: (25),
        }));
        const __VLS_242 = __VLS_241({
            size: (25),
        }, ...__VLS_functionalComponentArgsRest(__VLS_241));
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({
            id: "create-title",
        });
        (__VLS_unwrap(t, {})("Create a project", "创建项目"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
        (__VLS_unwrap(t, {})("Keep each application’s data and API keys separate.", "为每个应用独立管理数据和密钥。"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
            ...{ onSubmit: (createProject) },
        });
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        (__VLS_unwrap(t, {})("Project name", "项目名称"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            placeholder: "resume-api",
            maxlength: "128",
            required: true,
        });
        (newProject.value.name);
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        (__VLS_unwrap(t, {})("Environment", "环境"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            maxlength: "128",
            required: true,
        });
        (newProject.value.environment);
        if (error.value) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "notice error" },
            });
            /** @type {__VLS_StyleScopedClasses['notice']} */ ;
            /** @type {__VLS_StyleScopedClasses['error']} */ ;
            (error.value);
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ class: "btn primary full" },
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        /** @type {__VLS_StyleScopedClasses['primary']} */ ;
        /** @type {__VLS_StyleScopedClasses['full']} */ ;
        (__VLS_unwrap(t, {})("Create project", "创建项目"));
    }
    if (verification.value.open) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "modal-backdrop" },
        });
        /** @type {__VLS_StyleScopedClasses['modal-backdrop']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
            role: "dialog",
            'aria-modal': "true",
            ...{ class: "modal" },
            'aria-labelledby': "verify-title",
        });
        /** @type {__VLS_StyleScopedClasses['modal']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: // @ts-ignore
                (...[$event]) => {
                    void $event;
                    if (!(verification.value.open))
                        throw 0;
                    return (verification.value.open = false);
                    // @ts-ignore
                    [t, t, t, t, t, error, error, newProject, newProject, verification, verification,];
                } },
            ...{ class: "modal-close icon-button" },
        });
        /** @type {__VLS_StyleScopedClasses['modal-close']} */ ;
        /** @type {__VLS_StyleScopedClasses['icon-button']} */ ;
        let __VLS_245;
        /** @ts-ignore @type { | typeof __VLS_components.X} */
        X;
        // @ts-ignore
        const __VLS_246 = __VLS_asFunctionalComponent1(__VLS_245, new __VLS_245({
            // @ts-ignore
            size: (20),
        }));
        const __VLS_247 = __VLS_246({
            size: (20),
        }, ...__VLS_functionalComponentArgsRest(__VLS_246));
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "modal-icon" },
        });
        /** @type {__VLS_StyleScopedClasses['modal-icon']} */ ;
        let __VLS_250;
        /** @ts-ignore @type { | typeof __VLS_components.ShieldCheck} */
        ShieldCheck;
        // @ts-ignore
        const __VLS_251 = __VLS_asFunctionalComponent1(__VLS_250, new __VLS_250({
            // @ts-ignore
            size: (26),
        }));
        const __VLS_252 = __VLS_251({
            size: (26),
        }, ...__VLS_functionalComponentArgsRest(__VLS_251));
        __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({
            id: "verify-title",
        });
        (__VLS_unwrap(t, {})("Verify it’s you", "验证你的身份"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
        (__VLS_unwrap(t, {})("Enter the code sent to", "请输入发送至"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.b, __VLS_intrinsics.b)({});
        (session.value?.email);
        (__VLS_unwrap(t, {})("to authorize this change.", "的验证码以授权此次变更。"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "verification-target" },
        });
        /** @type {__VLS_StyleScopedClasses['verification-target']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.code, __VLS_intrinsics.code)({});
        (verification.value.action);
        __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
        (verification.value.target);
        if (verification.value.error) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "notice error" },
            });
            /** @type {__VLS_StyleScopedClasses['notice']} */ ;
            /** @type {__VLS_StyleScopedClasses['error']} */ ;
            (verification.value.error);
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
            ...{ onSubmit: (confirm) },
        });
        __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
        (__VLS_unwrap(t, {})("Verification code", "验证码"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
            ...{ class: "code-input" },
            inputmode: "numeric",
            pattern: "[0-9]{6}",
            maxlength: "6",
            placeholder: "000000",
            autocomplete: "one-time-code",
            required: true,
        });
        (verification.value.code);
        /** @type {__VLS_StyleScopedClasses['code-input']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
        (__VLS_unwrap(t, {})("Expires in 5 minutes. Maximum 5 attempts.", "5 分钟内有效，最多尝试 5 次。"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ class: "btn primary full" },
            disabled: (verification.value.busy || !verification.value.challengeId),
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        /** @type {__VLS_StyleScopedClasses['primary']} */ ;
        /** @type {__VLS_StyleScopedClasses['full']} */ ;
        (verification.value.busy
            ? __VLS_unwrap(t, {})("Please wait…", "请稍候…")
            : __VLS_unwrap(t, {})("Verify & confirm", "验证并确认"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (sendCode) },
            ...{ class: "text-button" },
            type: "button",
            disabled: (verification.value.busy),
        });
        /** @type {__VLS_StyleScopedClasses['text-button']} */ ;
        (__VLS_unwrap(t, {})("Resend code", "重新发送"));
    }
    // @ts-ignore
    [t, t, t, t, t, t, t, t, session, verification, verification, verification, verification, verification, verification, verification, verification, verification,];
    return (await import('vue')).defineComponent({});
})();
