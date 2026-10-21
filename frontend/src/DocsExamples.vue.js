import { ref } from "vue";
import { examples } from "./examples";
import { t } from "./lib";
const selected = ref("cURL"), copied = ref(false);
async function copy() {
    await navigator.clipboard.writeText(examples[selected.value] || "");
    copied.value = true;
    setTimeout(() => (copied.value = false), 1500);
}
const __VLS_ctx = {};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
void __VLS_ctx, __VLS_components, __VLS_intrinsics, __VLS_directives;
// @ts-ignore
__VLS_withDotValue(selected, {});
// @ts-ignore
__VLS_withDotValue(copied, {});
// @ts-ignore
__VLS_withDotValue(examples, {});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "tabs" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['tabs']} */ ;
const __VLS_0 = __VLS_tryAsConstant((examples.value));
for (const [_, name] of __VLS_vFor(__VLS_nonNull(__VLS_0))) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: // @ts-ignore
            (...[$event]) => {
                void $event;
                return (selected.value = String(name));
                // @ts-ignore
                [examples, selected,];
            } },
        key: (name),
        ...{ class: ({ active: selected.value === name }) },
    });
    /** @type {__VLS_StyleScopedClasses['active']} */ ;
    (name);
    // @ts-ignore
    [selected,];
}
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
(__VLS_unwrap(t, {})("Run from the repository root. First run node scripts/generate-example.mjs to create a current examples/batch.json. Set METERTRAIL_ENDPOINT and METERTRAIL_API_KEY.", "在仓库根目录执行，先运行 node scripts/generate-example.mjs 生成当前 examples/batch.json，再设置 METERTRAIL_ENDPOINT 与 METERTRAIL_API_KEY。"));
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "code-box" },
});
/** @type {__VLS_StyleScopedClasses['code-box']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
(selected.value);
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (copy) },
});
(copied.value ? __VLS_unwrap(t, {})("Copied", "已复制") : __VLS_unwrap(t, {})("Copy", "复制"));
__VLS_asFunctionalElement1(__VLS_intrinsics.pre, __VLS_intrinsics.pre)({});
(examples.value[selected.value]);
// @ts-ignore
[examples, selected, selected, t, t, t, copied,];
const __VLS_export = (await import('vue')).defineComponent({});
export default {};
