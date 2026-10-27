import { writeFileSync, readFileSync } from 'node:fs';
import { sources } from './sync-scenario-examples.mjs';

const zh = `# SDK 接入示例与 ID 规则

本文与英文版使用完全相同的代码和参数，只翻译说明。Node.js、Python、Java 各提供单次模型调用、多次模型调用和两种评分目标的四份完整代码。

## ID 规则

| 字段 | 规则与使用方式 |
| --- | --- |
| eventId | 每条新事件在同一项目内唯一，恰好 36 个 Unicode 字符，不限制 UUID 格式。推荐 UUID；SDK 未传时自动生成。重试同一事件保留原值和原内容，不重新生成。常规 UUID 大小写视为同一事件；其他字符串区分大小写。 |
| objectId | 非空字符串，最多 128 个 UTF-8 字节（不是 128 个汉字）。唯一范围是同一项目、同一 type。它代表一条具体请求、步骤或评分，不是业务功能名称。新对象换 ID；更新同一对象保留 ID 并提高 revision。 |
| traceId | Trace、Observation 必填，恰好 32 个十六进制字符，仅 0–9、a–f、A–F；建议统一小写。一笔业务请求生成一次，所属 Trace 与所有 Observation 共用；新业务请求换值。它负责关联调用链，不能代替 objectId。 |
| spanId | Observation 必填，恰好 16 个十六进制字符。每个步骤分配不同值；同一步骤重试保留。 |
| parentSpanId | 可省略或为 null；有父步骤时填父 Observation 的 spanId，恰好 16 个十六进制字符，不是父 objectId。 |
| targetType | Score 的目标类型，只能是 trace 或 observation。 |
| targetId | Score 必填，最多 128 个 UTF-8 字节；填同一项目中目标 Trace 或 Observation 的 objectId，由 targetType 决定。不是 eventId、traceId 或 spanId。 |
| revision | 可省略，默认 1。只上报最终结果时保持 1；确实修改已存在对象才递增。版本号与请求是否结束无关。 |

SDK 自动生成 eventId，不会替你生成 objectId、traceId、spanId 或选择评分目标。保留待重试事件；重新调用 record 且省略 eventId 会生成新事件身份。

## 两种评分目标

你说的 scope 应为 score（评分）。它可以评价整次请求，也可以只评价某个模型步骤，按业务选择。

下列两段是替代方案：前者评价整次请求，后者评价第一步。示例 ID 一致，便于看清关联关系；实际请使用已成功处理的目标 objectId。Score 自己也有独立的 objectId。
`;
const en = `# SDK examples and ID rules

This guide uses exactly the same code and parameters as the Chinese version; only explanatory text is translated. Each Node.js, Python and Java section includes complete examples for one model call, multiple model calls and both score targets.

## ID rules

| Field | Rule and usage |
| --- | --- |
| eventId | Unique per new event within the same project; exactly 36 Unicode characters, with no UUID format requirement. UUIDs are recommended; SDKs generate one when omitted. Retrying an event preserves its original ID and content. Conventional UUID spellings are case-insensitive; other strings are case-sensitive. |
| objectId | A nonblank string of at most 128 UTF-8 bytes, unique within project + type. Identifies a particular request, step or score, not a business feature. New objects use new IDs; updates retain the ID and increase revision. |
| traceId | Required for Trace and Observation; exactly 32 hexadecimal characters (0–9, a–f, A–F). Use lowercase consistently. Generate once per application request and share across its Trace and Observations; use a new value for a new request. This groups the trace and does not replace objectId. |
| spanId | Required for Observation; exactly 16 hexadecimal characters. Assign distinct values to distinct steps; preserve on retry. |
| parentSpanId | Optional or null; otherwise the parent Observation's spanId, exactly 16 hexadecimal characters, not its objectId. |
| targetType | Score target type: trace or observation. |
| targetId | Required for Score; at most 128 UTF-8 bytes. The target Trace or Observation's objectId in the same project, selected by targetType. Never its eventId, traceId or spanId. |
| revision | Optional; defaults to 1. Keep 1 when reporting final results only; increase only to update an existing object. It does not indicate whether a request has completed. |

SDKs generate eventId, but do not generate objectId, traceId or spanId, or select the score target for you. Retain events for retries; calling record again without eventId generates a new event identity.

## Two score targets

The event type is score, not scope. A score may evaluate the whole request or one model step, according to your application.

These are alternatives: the first evaluates the whole request, the second its first step. IDs match across examples to make the relationships clear; use the objectId of a successfully processed target in practice. Each Score has its own independent objectId.
`;
const id='0123456789abcdef0123456789abcdef';
const targets=['trace','observation'].map((type,i)=>({type:'score',objectId:`score-feedback-${i+1}`,data:{name:'User satisfaction',targetType:type,targetId:type==='trace'?`trace-${id}`:`obs-${id}-1`,valueType:'numeric',value:1}}));
const targetCode=targets.map(event=>'```json\n'+JSON.stringify(event,null,2)+'\n```').join('\n\n');
for(const [lang,intro] of [['zh-CN',zh],['en',en]]) {
  let body=intro+'\n'+targetCode+'\n\n';
  body+=lang==='zh-CN'?'## 运行说明\n\n按源码 SDK 仓库说明安装 SDK；在服务端设置 METERTRAIL_ENDPOINT=https://metertrail.com 和 METERTRAIL_API_KEY。Node/Python 从 SDK 仓库根目录运行；Java 将 SDK 与 Jackson 加入 classpath。评分示例先设置 METERTRAIL_TRACE_ID 为之前打印的 traceId，评价步骤的示例指向第一条 Observation，评价整次请求的示例指向 Trace；评价整次请求时按上面的目标规则同时修改 targetType、targetId。示例中的 now 使用运行时当前时间，耗时和 Token 是演示值，请替换成实际值。\n\n':'## Running the examples\n\nInstall the source SDK as described in its repository. Set METERTRAIL_ENDPOINT=https://metertrail.com and METERTRAIL_API_KEY on the server. Run Node/Python from the SDK repository root; add the SDK and Jackson to the Java classpath. For scores, set METERTRAIL_TRACE_ID to the earlier printed traceId. The step-score code targets the first Observation; the whole-request score targets the Trace; to evaluate the whole request, change both targetType and targetId using the rules above. Timestamps use the runtime current time; durations and tokens are illustrative and must be replaced with measured values.\n\n';
  for(const language of ['Node.js','Python','Java']) {
    body+=`## ${language}\n\n`;
    for(const [kind,z,e] of [['single','一次模型调用：一起发送 Trace + Observation','One model call: Trace + Observation together'],['multiple','多次模型调用：分组发送，共用 traceId','Multiple calls: separate groups sharing traceId'],['score-trace','评价整次请求：指向 Trace 的 objectId','Score the whole request: target the Trace objectId'],['score','可选评分：单独发送，指向第一条 Observation','Optional score: sent separately, targeting the first Observation']]) {
      body+=`### ${lang==='zh-CN'?z:e}\n\n\`\`\`${language==='Node.js'?'javascript':language.toLowerCase()}\n${sources[language][kind]}\`\`\`\n\n`;
    }
  }
  const path=`docs/sdk-id-guide.${lang}.md`;
  if(process.argv.includes('--check')) {
    if(readFileSync(path,'utf8').replace(/\r\n/g,'\n')!==body) throw Error(`${path} out of sync`);
  } else writeFileSync(path,body);
}
