import { language, setLanguage } from "./lib";
const __VLS_ctx = {};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
void __VLS_ctx, __VLS_components, __VLS_intrinsics, __VLS_directives;
let __VLS_0;
/** @ts-ignore @type { | typeof __VLS_components.RouterView} */
RouterView;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent1(__VLS_0, new __VLS_0({
// @ts-ignore
}));
const __VLS_2 = __VLS_1({}, ...__VLS_functionalComponentArgsRest(__VLS_1));
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "language-floating" },
});
/** @type {__VLS_StyleScopedClasses['language-floating']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.select, __VLS_intrinsics.select)({
    ...{ onChange: // @ts-ignore
        (...[$event]) => {
            void $event;
            return (__VLS_unwrap(setLanguage, {})($event.target.value));
            // @ts-ignore
            [setLanguage,];
        } },
    'aria-label': "Language / 语言",
    value: (__VLS_unwrap(language, {})),
});
__VLS_asFunctionalElement1(__VLS_intrinsics.option, __VLS_intrinsics.option)({
    value: "en",
});
__VLS_asFunctionalElement1(__VLS_intrinsics.option, __VLS_intrinsics.option)({
    value: "zh-CN",
});
// @ts-ignore
[language,];
const __VLS_export = (await import('vue')).defineComponent({});
export default {};
