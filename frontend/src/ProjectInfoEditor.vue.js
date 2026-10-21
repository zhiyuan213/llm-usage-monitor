import { reactive, ref, watch } from "vue";
import { api, t, errorText } from "./lib";
const { defineProps, defineEmits, } = {};
const props = defineProps(), emit = defineEmits(), form = reactive({ name: "", environment: "" }), error = ref(""), busy = ref(false);
watch(() => props.project?.id, () => {
    form.name = props.project?.name || "";
    form.environment = props.project?.environment || "";
}, { immediate: true });
async function save() {
    busy.value = true;
    try {
        await api("/projects/" + props.project.id + "/settings", form);
        emit("saved");
    }
    catch (e) {
        error.value = errorText(e);
    }
    finally {
        busy.value = false;
    }
}
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
__VLS_withDotValue(form, {});
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "panel form-panel" },
});
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['form-panel']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
(__VLS_unwrap(t, {})("Project details", "项目资料"));
if (error.value) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "notice error" },
    });
    /** @type {__VLS_StyleScopedClasses['notice']} */ ;
    /** @type {__VLS_StyleScopedClasses['error']} */ ;
    (error.value);
}
__VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
    ...{ onSubmit: (save) },
    ...{ class: "form-grid" },
});
/** @type {__VLS_StyleScopedClasses['form-grid']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
(__VLS_unwrap(t, {})("Project name", "项目名称"));
__VLS_asFunctionalElement1(__VLS_intrinsics.input)({
    maxlength: "128",
    required: true,
});
(form.value.name);
__VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
(__VLS_unwrap(t, {})("Environment", "环境"));
__VLS_asFunctionalElement1(__VLS_intrinsics.input)({
    maxlength: "128",
    required: true,
});
(form.value.environment);
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ class: "btn primary" },
    disabled: (__VLS_unwrap(busy, {})),
});
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['primary']} */ ;
(__VLS_unwrap(t, {})("Save changes", "保存变更"));
// @ts-ignore
[t, t, t, t, error, error, form, form, busy,];
const __VLS_export = (await import('vue')).defineComponent({
    __typeEmits: {},
    __typeProps: {},
});
export default {};
