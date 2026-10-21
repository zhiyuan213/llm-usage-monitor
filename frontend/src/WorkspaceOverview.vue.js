import { onMounted, ref } from "vue";
import { api, t, n, money, date, errorText } from "./lib";
const { defineEmits, } = {};
const data = ref({}), error = ref("");
const emit = defineEmits();
onMounted(async () => {
    try {
        data.value = await api("/workspace");
    }
    catch (e) {
        error.value = errorText(e);
    }
});
const __VLS_ctx = {
    ...{},
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
__VLS_withDotValue(data, {});
if (error.value) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "notice error" },
    });
    /** @type {__VLS_StyleScopedClasses['notice']} */ ;
    /** @type {__VLS_StyleScopedClasses['error']} */ ;
    (error.value);
}
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "panel form-panel" },
});
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['form-panel']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
(data.value.workspace?.name);
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
(__VLS_unwrap(t, {})("One shared subscription across all your projects. Model costs below include retained data; monthly units follow the current billing period.", "所有项目共享一个订阅。模型成本包括保留期内的数据，月度 units 按当前账期统计。"));
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "metrics" },
});
/** @type {__VLS_StyleScopedClasses['metrics']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
(__VLS_unwrap(t, {})("Current plan", "当前套餐"));
__VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
(data.value.workspace?.plan);
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
(__VLS_unwrap(t, {})("Current-period units", "本账期 units"));
__VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
(__VLS_unwrap(n, {})(data.value.workspace?.used_units));
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
(__VLS_unwrap(t, {})("Projects", "项目"));
__VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
(data.value.projects?.length || 0);
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
(__VLS_unwrap(t, {})("Period ends", "账期结束"));
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
(__VLS_unwrap(date, {})(data.value.workspace?.period_end));
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "panel table-wrap" },
});
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['table-wrap']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.table, __VLS_intrinsics.table)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.thead, __VLS_intrinsics.thead)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
(__VLS_unwrap(t, {})("Project", "项目"));
__VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
(__VLS_unwrap(t, {})("Environment", "环境"));
__VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
(__VLS_unwrap(t, {})("Traces", "调用"));
__VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
(__VLS_unwrap(t, {})("Objects", "对象"));
__VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
(__VLS_unwrap(t, {})("Model cost (USD)", "模型成本 (USD)"));
__VLS_asFunctionalElement1(__VLS_intrinsics.th, __VLS_intrinsics.th)({});
(__VLS_unwrap(t, {})("Unknown costs", "未知成本调用"));
__VLS_asFunctionalElement1(__VLS_intrinsics.tbody, __VLS_intrinsics.tbody)({});
const __VLS_0 = __VLS_tryAsConstant((data.value.projects || []));
for (const [p] of __VLS_vFor(__VLS_nonNull(__VLS_0))) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: // @ts-ignore
            (...[$event]) => {
                void $event;
                return (__VLS_unwrap(emit, {})('select', p.id));
                // @ts-ignore
                [error, error, data, data, data, data, data, data, t, t, t, t, t, t, t, t, t, t, t, n, date, emit,];
            } },
        ...{ class: "text-button" },
    });
    /** @type {__VLS_StyleScopedClasses['text-button']} */ ;
    (p.name);
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    (p.environment);
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    (__VLS_unwrap(n, {})(p.traces));
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    (__VLS_unwrap(n, {})(p.objects));
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    (__VLS_unwrap(money, {})(p.cost));
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({});
    (__VLS_unwrap(n, {})(p.unknown_costs));
    // @ts-ignore
    [n, n, n, money,];
}
if (!data.value.projects?.length) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.tr, __VLS_intrinsics.tr)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.td, __VLS_intrinsics.td)({
        colspan: "6",
        ...{ class: "empty-cell" },
    });
    /** @type {__VLS_StyleScopedClasses['empty-cell']} */ ;
    let __VLS_1;
    /** @ts-ignore @type { | typeof __VLS_components.RouterLink | typeof __VLS_components.RouterLink} */
    RouterLink;
    // @ts-ignore
    const __VLS_2 = __VLS_asFunctionalComponent1(__VLS_1, new __VLS_1({
        // @ts-ignore
        to: "/app/projects",
    }));
    const __VLS_3 = __VLS_2({
        to: "/app/projects",
    }, ...__VLS_functionalComponentArgsRest(__VLS_2));
    const { default: __VLS_6 } = __VLS_nonNull(__VLS_4.slots);
    (__VLS_unwrap(t, {})("Create your first project →", "创建第一个项目 →"));
    // @ts-ignore
    [data, t,];
    var __VLS_4;
}
// @ts-ignore
[];
const __VLS_export = (await import('vue')).defineComponent({
    __typeEmits: {},
});
export default {};
