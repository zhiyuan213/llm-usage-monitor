import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { api, t, errorText, session } from "./lib";
const router = useRouter(), error = ref(""), notice = ref(""), busy = ref(false);
const email = reactive({
    email: "",
    oldChallengeId: "",
    newChallengeId: "",
    oldCode: "",
    newCode: "",
}), deletion = reactive({ challengeId: "", code: "", confirm: "" });
async function run(fn) {
    busy.value = true;
    error.value = "";
    try {
        await fn();
    }
    catch (e) {
        error.value = errorText(e);
    }
    finally {
        busy.value = false;
    }
}
async function emailCode(action) {
    await run(async () => {
        const r = await api("/account/email/challenge", {
            email: email.email,
            action,
        });
        if (action === "email.old")
            email.oldChallengeId = r.challengeId;
        else
            email.newChallengeId = r.challengeId;
        notice.value = t("Code sent. Wait 60 seconds before requesting the other code. Both expire after 5 minutes.", "验证码已发送，请等待 60 秒再发送另一封验证码。两者均为 5 分钟内有效。");
    });
}
const __VLS_ctx = {};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
void __VLS_ctx, __VLS_components, __VLS_intrinsics, __VLS_directives;
// @ts-ignore
__VLS_withDotValue(error, {});
// @ts-ignore
__VLS_withDotValue(notice, {});
// @ts-ignore
__VLS_withDotValue(session, {});
// @ts-ignore
__VLS_withDotValue(router, {});
// @ts-ignore
__VLS_withDotValue(email, {});
// @ts-ignore
__VLS_withDotValue(busy, {});
// @ts-ignore
__VLS_withDotValue(deletion, {});
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "panel form-panel" },
});
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['form-panel']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
(__VLS_unwrap(t, {})("Change email address", "修改邮箱"));
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
(__VLS_unwrap(t, {})("Prove control of both addresses. Changing the target address requires new codes. All sessions are revoked after the change.", "需要验证旧邮箱与新邮箱。修改目标地址后必须重新获取验证码，变更后撤销所有会话。"));
if (error.value) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "notice error" },
    });
    /** @type {__VLS_StyleScopedClasses['notice']} */ ;
    /** @type {__VLS_StyleScopedClasses['error']} */ ;
    (error.value);
}
if (notice.value) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "notice" },
    });
    /** @type {__VLS_StyleScopedClasses['notice']} */ ;
    (notice.value);
}
__VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
    ...{ onSubmit: // @ts-ignore
        (...[$event]) => {
            void $event;
            __VLS_withDotValue(session, {});
            return (run(async () => {
                await __VLS_unwrap(api, {})('/account/email', email.value);
                session.value = null;
                router.value.push('/login');
            }));
            // @ts-ignore
            [t, t, error, error, notice, notice, api, email, session, router,];
        } },
});
__VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
(__VLS_unwrap(t, {})("New email", "新邮箱"));
__VLS_asFunctionalElement1(__VLS_intrinsics.input)({
    ...{ onInput: // @ts-ignore
        (...[$event]) => {
            void $event;
            email.value.oldChallengeId = '';
            email.value.newChallengeId = '';
            ;
            // @ts-ignore
            [t, email, email,];
        } },
    type: "email",
    required: true,
});
(email.value.email);
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "inline-form" },
});
/** @type {__VLS_StyleScopedClasses['inline-form']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: // @ts-ignore
        (...[$event]) => {
            void $event;
            return (emailCode('email.old'));
            // @ts-ignore
            [email,];
        } },
    ...{ class: "btn" },
    type: "button",
    disabled: (busy.value || !email.value.email),
});
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
(__VLS_unwrap(t, {})("Send to current email", "发送至旧邮箱"));
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: // @ts-ignore
        (...[$event]) => {
            void $event;
            return (emailCode('email.new'));
            // @ts-ignore
            [t, email, busy,];
        } },
    ...{ class: "btn" },
    type: "button",
    disabled: (busy.value || !email.value.email),
});
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
(__VLS_unwrap(t, {})("Send to new email", "发送至新邮箱"));
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "form-grid" },
});
/** @type {__VLS_StyleScopedClasses['form-grid']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
(__VLS_unwrap(t, {})("Current email code", "旧邮箱验证码"));
__VLS_asFunctionalElement1(__VLS_intrinsics.input)({
    pattern: "[0-9]{6}",
    required: true,
});
(email.value.oldCode);
__VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
(__VLS_unwrap(t, {})("New email code", "新邮箱验证码"));
__VLS_asFunctionalElement1(__VLS_intrinsics.input)({
    pattern: "[0-9]{6}",
    required: true,
});
(email.value.newCode);
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ class: "btn primary" },
    disabled: (busy.value || !email.value.oldChallengeId || !email.value.newChallengeId),
});
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['primary']} */ ;
(__VLS_unwrap(t, {})("Change email", "修改邮箱"));
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "panel form-panel danger-zone" },
});
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['form-panel']} */ ;
/** @type {__VLS_StyleScopedClasses['danger-zone']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
(__VLS_unwrap(t, {})("Close account", "注销账号"));
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
(__VLS_unwrap(t, {})("Cancel any active subscription first. API keys and sessions are revoked immediately. Project details are deleted asynchronously; minimal billing and audit records are retained.", "请先取消有效订阅。密钥与会话立即撤销，项目明细异步删除，必要计费和审计记录保留。"));
__VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
    ...{ onSubmit: // @ts-ignore
        (...[$event]) => {
            void $event;
            __VLS_withDotValue(session, {});
            return (run(async () => {
                await __VLS_unwrap(api, {})('/account/delete', deletion.value);
                session.value = null;
                router.value.push('/');
            }));
            // @ts-ignore
            [t, t, t, t, t, t, api, email, email, email, email, email, session, router, busy, busy, deletion,];
        } },
});
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: // @ts-ignore
        (...[$event]) => {
            void $event;
            __VLS_withDotValue(session, {});
            return (run(async () => {
                const r = await __VLS_unwrap(api, {})('/verification', {
                    action: 'account.delete',
                    target: session.value.id,
                });
                deletion.value.challengeId = r.challengeId;
            }));
            // @ts-ignore
            [api, session, deletion,];
        } },
    type: "button",
    ...{ class: "btn" },
    disabled: (busy.value),
});
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
(__VLS_unwrap(t, {})("Send verification code", "发送验证码"));
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "form-grid" },
});
/** @type {__VLS_StyleScopedClasses['form-grid']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
(__VLS_unwrap(t, {})("Email code", "邮箱验证码"));
__VLS_asFunctionalElement1(__VLS_intrinsics.input)({
    pattern: "[0-9]{6}",
    required: true,
});
(deletion.value.code);
__VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
(__VLS_unwrap(t, {})("Type DELETE to confirm", "输入 DELETE 确认"));
__VLS_asFunctionalElement1(__VLS_intrinsics.input)({
    pattern: "DELETE",
    required: true,
});
(deletion.value.confirm);
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ class: "btn danger-button" },
    disabled: (busy.value || !deletion.value.challengeId),
});
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['danger-button']} */ ;
(__VLS_unwrap(t, {})("Close my account", "注销账号"));
// @ts-ignore
[t, t, t, t, busy, busy, deletion, deletion, deletion,];
const __VLS_export = (await import('vue')).defineComponent({});
export default {};
