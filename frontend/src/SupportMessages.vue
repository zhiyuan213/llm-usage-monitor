<script setup lang="ts">
import {MessageSquare, Mail, ShieldCheck, Plus, Send} from "lucide-vue-next";
import { useToastNotice } from "./toast";
import {usePageSize} from "./page-size";
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, date, errorText, session, t } from "./lib";
import QueryControls from "./QueryControls.vue";
const props = defineProps<{ admin: boolean; refreshToken: boolean }>();
const route = useRoute(), router = useRouter();
const base = computed(() => props.admin ? "/admin/messages" : "/messages");
const routeBase = computed(() => props.admin ? "/app/admin-messages" : "/app/messages");
const threadId = computed(() => String(route.params.detail || ""));
const items = ref<any[]>([]), total = ref(0), page = ref(0), size=usePageSize();
const detail = ref<any>(null), busy = ref(false), loading = ref(false), olderBusy = ref(false);
const error = ref(""), notice = useToastNotice(), composing = ref(false), reply = ref("");
const draft = reactive({title:"",body:""});
let epoch = 0, alive = true;
let pending = { signature: "", id: "" };
const bytes = (value: string) => new TextEncoder().encode(value).length;
const draftValid = computed(() => !!draft.title.trim() && !!draft.body.trim() && bytes(draft.title)<=240 && bytes(draft.body)<=8000);
const replyValid = computed(() => !!reply.value.trim() && bytes(reply.value)<=8000);
function clientId(value: unknown) {
  const signature=JSON.stringify(value);
  if (pending.signature!==signature) pending={signature,id:crypto.randomUUID()};
  return pending.id;
}
async function load() {
  const request=++epoch;loading.value=true;error.value="";
  try {
    if(props.admin && !session.value?.isAdmin) throw new Error(t("Administrator access is required.","仅管理员可以访问。"));
    const list=await api(`${base.value}?page=${page.value}&size=${size.value}`);
    if(!alive || request!==epoch)return;
    if(page.value>0 && list.total<=page.value*size.value) {page.value=Math.max(0,Math.ceil(list.total/size.value)-1);await load();return;}
    items.value=list.items;total.value=list.total;
    const conversation=threadId.value ? await api(`${base.value}/${threadId.value}`) : null;
    if(alive && request===epoch)detail.value=conversation;
  } catch(e) {if(alive && request===epoch) {detail.value=null;error.value=e instanceof Error && !("code" in e)?e.message:errorText(e);}}
  finally {if(alive && request===epoch)loading.value=false;}
}
watch([() => props.refreshToken, threadId], () => {reply.value="";detail.value=null;notice.value="";composing.value=false;void load();}, {immediate:true});
onBeforeUnmount(()=>{alive=false;++epoch;});
async function turn(next: number) {page.value=next;await load();}
async function resize(next: number) {size.value=next;page.value=0;await load();}
async function create() {
  if(busy.value || !draftValid.value)return;
  busy.value=true;error.value="";notice.value="";
  try {
    const body={title:draft.title.trim(),body:draft.body.trim()};
    const result=await api("/messages",{...body,clientId:clientId(body)});
    if(!alive)return;
    draft.title="";draft.body="";pending={signature:"",id:""};page.value=0;composing.value=false;
    await router.push(`${routeBase.value}/${result.id}`);
    notice.value=t("Message sent.","消息已发送。");
  } catch(e) {if(alive)error.value=errorText(e);}
  finally {if(alive)busy.value=false;}
}
async function sendReply() {
  if(busy.value || !replyValid.value || !detail.value)return;
  const id=threadId.value;
  busy.value=true;error.value="";notice.value="";
  try {
    const body=reply.value.trim();
    await api(`${base.value}/${id}/replies`,{body,clientId:clientId({id,body,admin:props.admin})});
    if(!alive || id!==threadId.value)return;
    reply.value="";pending={signature:"",id:""};
    notice.value=t("Reply sent.","回复已发送。");await load();
  } catch(e) {if(alive && id===threadId.value)error.value=errorText(e);}
  finally {if(alive)busy.value=false;}
}
async function older() {
  if(!detail.value?.hasMore || olderBusy.value)return;
  const id=threadId.value,request=epoch,before=detail.value.replies[0].sequence;
  olderBusy.value=true;error.value="";
  try {
    const result=await api(`${base.value}/${id}?before=${before}`);
    if(alive && request===epoch && id===threadId.value) {
      detail.value.replies=[...result.replies,...detail.value.replies];detail.value.hasMore=result.hasMore;
    }
  }catch(e){if(alive && request===epoch)error.value=errorText(e);}
  finally{if(alive)olderBusy.value=false;}
}
</script>

<template>
  <section class="support-messages" :aria-busy="loading">
    <header class="support-intro panel"><div class="support-intro-icon"><MessageSquare :size="24" /></div><div class="support-intro-copy"><h2>{{admin?t('Feedback inbox','反馈收件箱'):t('We are here to help','问题与建议，随时反馈')}}</h2><p>{{ admin ? t('Read and reply to user feedback. Replies stay in the original conversation.', '查看并回复用户反馈，所有回复保留在原消息会话中。') : t('Tell us what is not working or share a suggestion. Replies appear under your original message.', '遇到问题或有建议，可以在这里反馈。管理员的回复会显示在原消息下面。') }}</p><small><ShieldCheck :size="14" />{{t('Do not include passwords, API keys, payment details, or sensitive event content.','请勿发送密码、API 密钥、支付信息或敏感事件内容。')}}</small></div><a v-if="!admin" class="support-email" href="mailto:13428961230@163.com"><Mail :size="17" /><span>{{t('Email support','邮件联系')}}<strong>13428961230@163.com</strong></span></a></header>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>

    <div class="support-layout">
      <section class="panel support-list">
        <div class="support-heading"><h2>{{ admin ? t('User conversations', '用户会话') : t('My messages', '我的消息') }}</h2>
          <button v-if="!admin" class="btn primary" :disabled="busy" @click="composing=true;notice=''"><Plus :size="15" />{{ t('New message', '新建消息') }}</button>
        </div>
        <p v-if="loading && !items.length" role="status">{{ t('Loading…', '正在加载…') }}</p>
        <div v-else-if="!items.length" class="support-empty-list"><MessageSquare :size="28" /><strong>{{t('No messages yet','还没有反馈消息')}}</strong><p>{{t('Your conversations and replies will appear here.','新建消息后，可在这里查看反馈和回复。')}}</p></div>
        <RouterLink v-for="item in items" :key="item.id" :to="`${routeBase}/${item.id}`" :class="['support-thread', {selected: threadId === item.id}]" :aria-current="threadId === item.id ? 'page' : undefined">
          <strong>{{ item.title }}</strong>
          <span v-if="admin">{{ item.user_email }}</span>
          <span>{{ item.last_sender_kind === 'admin' ? t('Administrator replied', '管理员已回复') : t('Awaiting reply', '等待回复') }} · {{ item.reply_count }} {{ t('replies', '条回复') }}</span>
          <small>{{ date(item.updated_at) }}</small>
        </RouterLink>
        <QueryControls :page="page" :size="size" :total="total" :loading="loading || busy" @change="turn" @resize="resize" />
      </section>
      <section v-if="composing && !admin" class="panel support-conversation">
        <div class="support-heading"><h2>{{ t('New message', '新建消息') }}</h2><button class="text-button" :disabled="busy" @click="composing=false">{{ t('Cancel', '取消') }}</button></div>
        <form @submit.prevent="create">
          <label>{{ t('Subject', '标题') }}<input v-model="draft.title" required maxlength="240" :placeholder="t('Briefly describe your question or suggestion','简要描述你的问题或建议')" :disabled="busy" /></label>
          <small v-if="bytes(draft.title) > 240" class="muted" role="status">{{ t('The subject is too long. Please shorten it.', '标题太长，请精简后再发送。') }}</small>
          <label>{{ t('Message', '消息内容') }}<textarea v-model="draft.body" required maxlength="8000" rows="8" :placeholder="t('Describe what happened and what you expected.','描述遇到的问题、操作步骤和期望的结果。')" :disabled="busy"></textarea></label>
          <small v-if="bytes(draft.body) > 8000" class="muted" role="status">{{ t('The message is too long. Please shorten it or send it in parts.', '消息太长，请精简或分条发送。') }}</small>
          <button class="btn primary" :disabled="busy || !draftValid">{{ busy ? t('Sending…', '正在发送…') : t('Send message', '发送消息') }}</button>
        </form>
      </section>
      <section v-else-if="detail" class="panel support-conversation" :aria-label="t('Message conversation', '消息会话')">
        <article class="support-original">
          <h2>{{ detail.thread.title }}</h2>
          <p class="muted">{{ admin ? detail.thread.user_email : t('You', '你') }} · {{ date(detail.thread.created_at) }}</p>
          <p class="support-body">{{ detail.thread.body }}</p>
        </article>
        <h3>{{ t('Conversation replies', '会话回复') }}</h3>
        <button v-if="detail.hasMore" class="btn" :disabled="olderBusy" @click="older">{{ olderBusy ? t('Loading…', '正在加载…') : t('Load earlier replies', '加载更早回复') }}</button>
        <p v-if="!detail.thread.reply_count" class="muted">{{ t('No replies yet.', '暂无回复。') }}</p>
        <ol class="support-replies">
          <li v-for="message in detail.replies" :key="message.id" :class="{'support-admin-reply':message.sender_kind==='admin'}">
            <p class="support-reply-meta"><strong>{{ message.sender_kind==='admin' ? t('Administrator', '管理员') : t('User', '用户') }}</strong><time>{{ date(message.created_at) }}</time></p>
            <p class="support-body">{{ message.body }}</p>
          </li>
        </ol>
        <form v-if="detail.thread.reply_count<200" @submit.prevent="sendReply">
          <label>{{ t('Reply to this conversation', '回复此会话') }}<textarea v-model="reply" required maxlength="8000" rows="4" :disabled="busy || loading"></textarea></label>
          <small v-if="bytes(reply) > 8000" class="muted" role="status">{{ t('The reply is too long. Please shorten it or send it in parts.', '回复太长，请精简或分条发送。') }}</small>
          <button class="btn primary" :disabled="busy || loading || !replyValid">{{ busy ? t('Sending…', '正在发送…') : t('Send reply', '发送回复') }}</button>
        </form>
        <p v-else class="muted">{{ t('Reply limit reached. Start a new conversation.', '已达到回复上限，请新建会话。') }}</p>
      </section>
      <section v-else class="panel support-conversation support-empty-conversation"><div class="support-empty-art"><MessageSquare :size="34" /></div><h2>{{loading?t('Loading…','正在加载…'):t('Keep every conversation in one place','在这里查看完整会话')}}</h2><p>{{t('Select a message on the left to view its details and replies.','选择左侧消息，即可查看内容、跟进回复。')}}</p><button v-if="!admin&&!loading" class="btn primary" @click="composing=true;notice=''" :disabled="busy"><Send :size="15" />{{t('Write your first message','发起反馈')}}</button></section>
    </div>
  </section>
</template>
