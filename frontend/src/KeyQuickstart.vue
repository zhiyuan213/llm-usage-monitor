<script setup lang="ts">
import {computed,ref} from 'vue';
import {t} from './lib';
import {showToast} from './toast';
const props=defineProps<{apiKey:string}>();
const lang=ref('curl'),copied=ref(false),failed=ref(false);
// Build locally only: never send or persist the revealed credential.
const body=JSON.stringify({events:[{eventId:crypto.randomUUID(),type:'trace',objectId:crypto.randomUUID(),traceId:crypto.randomUUID().replaceAll('-',''),startedAt:new Date().toISOString(),data:{name:'First request',status:'success',durationMs:100,userId:'demo-user',customerId:'demo-customer',feature:'quickstart'}}]},null,2);
const code=computed(()=>{
 const headers=JSON.stringify({'Authorization':`Bearer ${props.apiKey}`,'Content-Type':'application/json'});
 if(lang.value==='node')return `const response = await fetch('https://metertrail.com/v1/events/batch', {\n  method: 'POST',\n  headers: ${headers},\n  body: JSON.stringify(${body})\n});\nconsole.log(response.status, await response.json());`;
 if(lang.value==='python')return `import json\nimport urllib.request\n\npayload = json.loads(r'''${body}''')\nrequest = urllib.request.Request(\n    'https://metertrail.com/v1/events/batch',\n    data=json.dumps(payload).encode('utf-8'),\n    headers=${headers},\n    method='POST'\n)\nwith urllib.request.urlopen(request) as response:\n    print(response.status, response.read().decode('utf-8'))`;
 return `curl 'https://metertrail.com/v1/events/batch' \\\n  -H 'Authorization: Bearer ${props.apiKey}' \\\n  -H 'Content-Type: application/json' \\\n  --data-raw '${body}'`;
});
async function copy(){try{await navigator.clipboard.writeText(code.value);copied.value=true;failed.value=false;showToast(t('Example copied.','示例已复制。'));}catch{failed.value=true;showToast(t('Could not copy the example.','复制示例失败。'),'error');}}
</script>
<template><section class="key-quickstart"><h3>{{t('Send your first request','发送第一条数据')}}</h3><p>{{t('This sample creates one Trace (1 unit). It contains no model call, Token usage or estimated model cost.','示例创建一条 Trace，消耗 1 unit；不包含模型调用、Token 或模型估算费用。')}}</p><div class="inline-form"><label>{{t('Example language','示例语言')}}<select v-model="lang" @change="copied=false"><option value="curl">cURL (Bash)</option><option value="node">Node.js</option><option value="python">Python</option></select></label><button type="button" class="btn" @click="copy">{{copied?t('Copied','已复制'):t('Copy example','复制示例')}}</button></div><p v-if="failed" role="alert">{{t('Clipboard unavailable. Select and copy the example.','剪贴板不可用，请选中示例复制。')}}</p><pre tabindex="0"><code>{{code}}</code></pre><p>{{t('202 / pending means received. Use the returned batchId to check the final result, then open Traces. Keep the same IDs when retrying this event.','202 / pending 表示已接收；使用返回的 batchId 查询最终结果，再查看调用记录。重试本事件时保留原 ID。')}}</p><RouterLink class="btn" to="/app/traces">{{t('View traces','查看调用记录')}}</RouterLink> <RouterLink to="/docs#protocol">{{t('Model usage example →','模型用量完整示例 →')}}</RouterLink></section></template>
