import { ref, watch, reactive } from "vue";
import { api, t, date, errorText } from "./lib";
const { defineProps, } = {};
const props = defineProps(), batches = ref([]), error = ref("");
const cancel = reactive({
    batch: "",
    challengeId: "",
    code: "",
    confirm: false,
    busy: false,
});
async function refresh() {
    try {
        batches.value = await api("/projects/" + props.project + "/batches");
    }
    catch (e) {
        error.value = errorText(e);
    }
}
async function replay(id) {
    try {
        await api("/projects/" + props.project + "/batches/" + id + "/replay", {});
        await refresh();
    }
    catch (e) {
        error.value = errorText(e);
    }
}
async function prepare(id) {
    error.value = "";
    cancel.batch = id;
    cancel.code = "";
    cancel.confirm = false;
    cancel.challengeId = "";
    cancel.busy = true;
    try {
        const r = await api("/verification", {
            action: "batch.cancel",
            target: props.project + ":" + id,
        });
        cancel.challengeId = r.challengeId;
    }
    catch (e) {
        error.value = errorText(e);
    }
    finally {
        cancel.busy = false;
    }
}
async function compensate() {
    cancel.busy = true;
    try {
        await api("/projects/" + props.project + "/batches/" + cancel.batch + "/cancel", cancel);
        cancel.batch = "";
        await refresh();
    }
    catch (e) {
        error.value = errorText(e);
    }
    finally {
        cancel.busy = false;
    }
}
watch(() => props.project, refresh, { immediate: true });
const __VLS_ctx = {
    ...{},
    ...{},
    ...{},
};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
void __VLS_ctx, __VLS_components, __VLS_intrinsics, __VLS_directives;
// @ts-ignore
__VLS_withDotValue(error, {});
// @ts-ignore
__VLS_withDotValue(cancel, {});
// @ts-ignore
__VLS_withDotValue(batches, {});
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "panel" },
});
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "panel-heading" },
});
/** @type {__VLS_StyleScopedClasses['panel-heading']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
(__VLS_unwrap(t, {})("Recent batches", "最近上报批次"));
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (refresh) },
    ...{ class: "btn" },
});
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
(__VLS_unwrap(t, {})("Refresh", "刷新"));
if (error.value && !cancel.value.batch) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "notice error" },
    });
    /** @type {__VLS_StyleScopedClasses['notice']} */ ;
    /** @type {__VLS_StyleScopedClasses['error']} */ ;
    (error.value);
}
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "table-wrap" },
});
/** @type {__VLS_StyleScopedClasses['table-wrap']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.table, __VLS_intrinsics.table)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.thead, __VLS_intrinsics.thead)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
(__VLS_unwrap(t, {})("Received (UTC)", "接收时间 (UTC)"));
__VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
(__VLS_unwrap(t, {})("Status", "状态"));
__VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
(__VLS_unwrap(t, {})("Accepted / duplicate / rejected", "接受 / 重复 / 拒绝"));
__VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
(__VLS_unwrap(t, {})("Details", "详情"));
__VLS_asFunctionalElement1(__VLS_intrinsics.tbody, __VLS_intrinsics.tbody)({});
const __VLS_0 = __VLS_tryAsConstant((batches.value));
for (const [b] of __VLS_vFor(__VLS_nonNull(__VLS_0))) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({
        key: (b.id),
    });
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    (__VLS_unwrap(date, {})(b.created_at));
    __VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({
        ...{ class: "mono" },
    });
    /** @type {__VLS_StyleScopedClasses['mono']} */ ;
    (b.id);
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: ([
                'badge',
                b.status === 'quarantined'
                    ? 'danger'
                    : b.status === 'completed'
                        ? 'success'
                        : '',
            ]) },
    });
    /** @type {__VLS_StyleScopedClasses['badge']} */ ;
    (b.status);
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    (b.accepted);
    (b.duplicates);
    (b.rejected);
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    if (b.errors.length) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.details, __VLS_intrinsics.details)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.summary, __VLS_intrinsics.summary)({});
        (b.errors.length);
        (__VLS_unwrap(t, {})("results", "条结果"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.pre, __VLS_intrinsics.pre)({});
        (JSON.stringify(b.errors, null, 2));
    }
    if (b.status === 'quarantined') {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "inline-form" },
        });
        /** @type {__VLS_StyleScopedClasses['inline-form']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: // @ts-ignore
                (...[$event]) => {
                    void $event;
                    if (!(b.status === 'quarantined'))
                        throw 0;
                    return (replay(b.id));
                    // @ts-ignore
                    [t, t, t, t, t, t, t, error, error, cancel, batches, date,];
                } },
            ...{ class: "btn" },
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        (__VLS_unwrap(t, {})("Replay after repair", "修复后重放"));
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: // @ts-ignore
                (...[$event]) => {
                    void $event;
                    if (!(b.status === 'quarantined'))
                        throw 0;
                    return (prepare(b.id));
                    // @ts-ignore
                    [t,];
                } },
            ...{ class: "btn danger-button" },
        });
        /** @type {__VLS_StyleScopedClasses['btn']} */ ;
        /** @type {__VLS_StyleScopedClasses['danger-button']} */ ;
        (__VLS_unwrap(t, {})("Cancel & release", "取消并释放额度"));
    }
    // @ts-ignore
    [t,];
}
if (!batches.value.length) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({
        colspan: "4",
        ...{ class: "empty-cell" },
    });
    /** @type {__VLS_StyleScopedClasses['empty-cell']} */ ;
    (__VLS_unwrap(t, {})("No batches received yet.", "尚未收到上报批次。"));
}
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "panel-foot" },
});
/** @type {__VLS_StyleScopedClasses['panel-foot']} */ ;
(__VLS_unwrap(t, {})("Quarantined batches remain durably stored with units reserved. Repair the underlying cause before replaying; replays never charge a second unit.", "隔离批次仍可靠保存并保留额度预留。修复故障后再重放，重放不重复扣费。"));
if (cancel.value.batch) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "modal-backdrop" },
    });
    /** @type {__VLS_StyleScopedClasses['modal-backdrop']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
        ...{ class: "modal" },
        role: "dialog",
        'aria-modal': "true",
        'aria-labelledby': "cancel-batch-title",
    });
    /** @type {__VLS_StyleScopedClasses['modal']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({
        id: "cancel-batch-title",
    });
    (__VLS_unwrap(t, {})("Mark batch as permanently failed", "将批次标记为永久失败"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_unwrap(t, {})("Only unprocessed objects without surviving updates are refunded. Closed billing periods require separate review. This batch cannot be replayed after cancellation.", "仅释放尚未处理且没有后续有效更新的对象额度。已关闭账期需独立审核。取消后该批次无法再重放。"));
    if (error.value) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "notice error" },
        });
        /** @type {__VLS_StyleScopedClasses['notice']} */ ;
        /** @type {__VLS_StyleScopedClasses['error']} */ ;
        (error.value);
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
        ...{ onSubmit: (compensate) },
    });
    __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
    (__VLS_unwrap(t, {})("Email verification code", "邮箱验证码"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
        pattern: "[0-9]{6}",
        maxlength: "6",
        required: true,
    });
    (cancel.value.code);
    __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({
        ...{ class: "check-label" },
    });
    /** @type {__VLS_StyleScopedClasses['check-label']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
        type: "checkbox",
        required: true,
    });
    (cancel.value.confirm);
    (__VLS_unwrap(t, {})("I confirm permanent failure and eligible unit release.", "我确认永久失败并释放符合条件的额度。"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ class: "btn danger-button" },
        disabled: (cancel.value.busy || !cancel.value.challengeId),
    });
    /** @type {__VLS_StyleScopedClasses['btn']} */ ;
    /** @type {__VLS_StyleScopedClasses['danger-button']} */ ;
    (__VLS_unwrap(t, {})("Confirm cancellation", "确认取消"));
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: // @ts-ignore
            (...[$event]) => {
                void $event;
                if (!(cancel.value.batch))
                    throw 0;
                return (cancel.value.batch = '');
                // @ts-ignore
                [t, t, t, t, t, t, t, error, error, cancel, cancel, cancel, cancel, cancel, cancel, batches,];
            } },
        ...{ class: "btn" },
        type: "button",
    });
    /** @type {__VLS_StyleScopedClasses['btn']} */ ;
    (__VLS_unwrap(t, {})("Keep batch", "保留批次"));
}
// @ts-ignore
[t,];
const __VLS_export = (await import('vue')).defineComponent({
    __typeProps: {},
});
export default {};
