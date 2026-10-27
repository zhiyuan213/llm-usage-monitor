# POST /v1/events/batch：完整入参、中文说明与三种事件示例

本文依据当前仓库接口校验代码整理。以下完整 JSON 包含三种事件，可以作为请求体结构参考；时间和各种 ID 应按实际请求生成。可选字段不意味着需要全部填写。

## 接口与项目归属

| 项目 | 说明 |
| --- | --- |
| 请求方式 | POST |
| 地址 | https://metertrail.com/v1/events/batch |
| 请求体格式 | JSON 对象 |
| Authorization | Bearer 后跟项目 API 密钥，服务端据此确定账号和项目 |
| Content-Type | application/json |
| Content-Encoding | 可不传；压缩请求时使用 gzip |
| project_id | 不需要在请求体中提交，也不能用它替代项目 API 密钥 |
| 单批事件数 | events 数组包含 1～500 个事件 |
| 单事件大小 | 最多 8 KiB，中文也计入 UTF-8 字节大小 |

使用 HTTP 客户端时，Host 通常会自动生成，不需要手动设置。

## 整个请求体的字段

| 字段 | 类型 | 必填 | 默认值 | 中文说明 |
| --- | --- | --- | --- | --- |
| schemaVersion | string | 否 | "1.0" | 接口协议版本；显式填写时只能是字符串 "1.0"，不是数字 1.0 |
| agent | object | 否 | 自动补齐下面三项 | 描述由哪个应用服务、运行环境和实例上报 |
| agent.serviceName | string | 否 | serviceName-1 | 应用服务名称，例如 document-api；最多 128 UTF-8 字节 |
| agent.environment | string | 否 | environment-1 | 应用运行环境，例如 production、test；最多 128 UTF-8 字节 |
| agent.instanceId | string | 否 | instanceId-1 | 上报实例名称，例如 document-api-1；最多 128 UTF-8 字节 |
| agent.sdkName | string | 否 | 无 | 使用的 SDK 名称；手动 REST 调用可省略，最多 128 UTF-8 字节 |
| agent.sdkVersion | string | 否 | 无 | 使用的 SDK 版本；手动 REST 调用可省略，最多 128 UTF-8 字节 |
| events | array | 是 | 无 | 此批上报的事件，每项是一个对象，三种 type 可以混在同一批中 |

默认值只适用于字段省略。不要用 null、空字符串或错误类型代替省略。agent.environment 是上报服务的环境描述，不用于选择或切换项目；项目归属仍由 API 密钥确定。

## 每个事件的公共字段

下面的字段位于 events 数组的每一项中，例如 events[0].eventId。

| 字段 | 类型 | 必填 | 格式及中文说明 |
| --- | --- | --- | --- |
| eventId | string | 是 | 恰好 36 个字符；不要求 UUID 格式。同一项目中标识一个事件；重试同一事件保留原值和内容 |
| type | string | 是 | trace、observation、score，具体业务含义见下面三个示例 |
| objectId | string | 是 | 自定义的具体记录标识，最多 128 UTF-8 字节；新记录换 ID，修订同一记录保留 ID |
| revision | integer | 否 | 默认 1；显式传入时必须是正整数，最大 9223372036854775807。它是修订版本，不是执行阶段 |
| traceId | string | trace、observation 必填 | 32 个十六进制字符，同一请求和内部步骤使用相同值；score 用 targetType 和 targetId 关联目标 |
| spanId | string | observation 必填 | 16 个十六进制字符，标识一个步骤；trace、score 通常不传 |
| parentSpanId | string 或 null | 否 | observation 的父步骤标识，16 个十六进制字符；无父步骤可省略或传 null |
| startedAt | string | 是 | UTC 时间，例如 2026-10-27T08:00:00Z；不能比服务器当前时间超前 5 分钟，还应符合接入时间窗口 |
| data | object | 是 | 事件的业务数据，允许字段随 type 不同，见下面的专用字段表 |

业务功能名称应放在 data.feature，不能把 objectId 理解成“文档分析这一类功能”。同一请求内多个步骤也需要各自独立的 objectId 和 spanId。

## trace / observation 的 data 字段

| 字段 | 类型 | 必填条件 | 中文说明 |
| --- | --- | --- | --- |
| name | string | trace 必填；observation 可选 | 请求或步骤的显示名称；trace 最多 256 UTF-8 字节 |
| status | string | 两者必填 | success 成功、error 失败、cancelled 已取消 |
| durationMs | integer | 两者必填 | 请求或步骤耗时，单位毫秒；0～9000000000000 |
| userId | string | 否 | 你业务系统的用户标识，最多 128 UTF-8 字节 |
| customerId | string | 否 | 你业务系统的客户标识，最多 128 UTF-8 字节 |
| feature | string | 否 | 业务功能标识，例如 document-analysis，最多 128 UTF-8 字节 |
| error | string | 否 | 简短错误说明，最多 1024 UTF-8 字节；不要填密码、密钥或敏感正文 |
| observationType | string | observation 必填 | 步骤类型，见下一表；trace 不需要填写 |
| provider | string | generation 必填 | 模型供应商，最多 64 UTF-8 字节 |
| model | string | generation 必填 | 模型名称，最多 256 UTF-8 字节；应与价格配置匹配 |
| usage | object | generation 必填 | 模型 Token 用量；其他 observation 类型可选 |
| usage.inputTokens | integer 或 null | 提供 usage 时必填 | 输入 Token 数；不知道时填 null，不要用 0 冒充已知值 |
| usage.outputTokens | integer 或 null | 提供 usage 时必填 | 输出 Token 数；不知道时填 null |
| usage.cachedInputTokens | integer 或 null | 提供 usage 时必填 | 输入中的缓存 Token 数；不知道时填 null |

Token 数值范围为 0～9000000000000。缓存数为已知值时，输入数也必须已知，且缓存数不能大于输入数。usage 中的三个字段都要有；没有缓存且确认缓存数为零时填写 0。

### observationType 的取值

| 取值 | 中文含义 | 示例 |
| --- | --- | --- |
| generation | 模型生成调用 | 调用语言模型生成分析结果 |
| span | 普通处理步骤 | 文档解析或业务处理 |
| tool | 工具调用 | 调用天气查询工具 |
| retrieval | 检索步骤 | 从知识库查找相关段落 |
| embedding | 向量生成步骤 | 将文本转成向量 |
| other | 其他步骤 | 不属于上面类型的处理过程 |

generation 的 provider、model、usage 必填。其他步骤是否能展示费用，还取决于系统支持的费用计算口径，不能仅凭上报步骤类型推断一定会计价。

## score 的 data 字段

| 字段 | 类型 | 必填 | 中文说明 |
| --- | --- | --- | --- |
| name | string | 是 | 评分名称，例如回答满意度；最多 128 UTF-8 字节 |
| targetType | string | 是 | trace 或 observation，表示评价哪类记录 |
| targetId | string | 是 | 被评价记录的 objectId，不是 eventId、traceId 或 spanId；最多 128 UTF-8 字节 |
| valueType | string | 是 | numeric 数值、boolean 布尔值、categorical 分类文本 |
| value | number / boolean / string | 是 | 类型必须与 valueType 一致；数值必须有限；分类文本非空、最多 128 个 Java 字符单元 |

例如 numeric 对应 1，boolean 对应 true，categorical 对应 "满意"。评分业务含义由你定义。

以下开始给出整个请求体的完整示例，以及每条记录的中文业务解释。

`type` 有三种取值：`trace`、`observation`、`score`，分别表示一次完整请求、请求中的一个步骤、对结果的评分。它们不是三个执行阶段，也不要求全部上报。

下面以“用户提交文档，AI 分析文档，用户评价结果”为例。

> 本文修正了聊天中的 score 示例：评分必须包含 targetType、targetId、valueType，不能只传 name 和 value。

## 完整批量上报请求

接口：`POST https://metertrail.com/v1/events/batch`

请求头：

```http
Authorization: Bearer <你的项目 API 密钥>
Content-Type: application/json
```

```json
{
  "schemaVersion": "1.0",
  "agent": {
    "serviceName": "document-api",
    "environment": "production",
    "instanceId": "document-api-1"
  },
  "events": [
    {
      "eventId": "550e8400-e29b-41d4-a716-446655440001",
      "type": "trace",
      "objectId": "trace-document-001",
      "revision": 1,
      "traceId": "0123456789abcdef0123456789abcdef",
      "startedAt": "2026-10-27T08:00:00Z",
      "data": {
        "name": "文档分析",
        "status": "success",
        "durationMs": 2500,
        "userId": "user-001",
        "customerId": "customer-001",
        "feature": "document-analysis"
      }
    },
    {
      "eventId": "550e8400-e29b-41d4-a716-446655440002",
      "type": "observation",
      "objectId": "obs-document-001",
      "revision": 1,
      "traceId": "0123456789abcdef0123456789abcdef",
      "spanId": "0123456789abcdef",
      "parentSpanId": null,
      "startedAt": "2026-10-27T08:00:00Z",
      "data": {
        "observationType": "generation",
        "status": "success",
        "durationMs": 2100,
        "provider": "example",
        "model": "example-model",
        "usage": {
          "inputTokens": 1500,
          "outputTokens": 400,
          "cachedInputTokens": 500
        }
      }
    },
    {
      "eventId": "550e8400-e29b-41d4-a716-446655440003",
      "type": "score",
      "objectId": "score-document-001",
      "revision": 1,
      "startedAt": "2026-10-27T08:01:00Z",
      "data": {
        "name": "回答满意度",
        "targetType": "trace",
        "targetId": "trace-document-001",
        "valueType": "numeric",
        "value": 1
      }
    }
  ]
}
```

## 1. trace：一次完整请求

第一条表示：客户 customer-001 下的用户 user-001，在北京时间 2026 年 10 月 27 日 16:00 发起一次“文档分析”，整个请求耗时 2.5 秒，执行成功。

| 参数 | 本例含义 |
| --- | --- |
| eventId | 本次上报事件的标识；重试同一事件时保留原值和内容 |
| type | trace，表示一次完整请求 |
| objectId | 这条请求记录的标识，不是业务功能名称 |
| revision | 记录的修订版本，1 表示第一版，不代表请求未结束 |
| traceId | 将完整请求和内部步骤关联起来的链路标识 |
| startedAt | 请求开始时间，Z 表示 UTC |
| data.name | 页面显示的请求名称：文档分析 |
| data.status | success 表示执行成功 |
| data.durationMs | 整个请求耗时，单位毫秒 |
| data.userId | 你业务系统中的用户标识 |
| data.customerId | 你业务系统中的客户标识 |
| data.feature | 业务功能标识：document-analysis |

## 2. observation：请求中的一次模型调用

第二条表示：这次文档分析过程中，调用一次 example-model 模型，耗时 2.1 秒，执行成功。输入 1,500 Token，输出 400 Token，其中缓存输入为 500 Token。

| 参数 | 本例含义 |
| --- | --- |
| eventId | 此模型调用上报事件的标识，与 Trace 的 eventId 不同 |
| type | observation，表示请求中的一个步骤 |
| objectId | 这条模型调用记录的标识 |
| revision | 第一版记录 |
| traceId | 与第一条相同，挂在同一次请求下面 |
| spanId | 这个步骤的标识，16 个十六进制字符 |
| parentSpanId | null 表示没有指定父步骤 |
| startedAt | 这个步骤的开始时间 |
| data.observationType | generation 表示模型生成调用 |
| data.status | success 表示执行成功 |
| data.durationMs | 此步骤耗时 2,100 毫秒 |
| data.provider | 模型供应商；example 是占位值，接入时换成真实值 |
| data.model | 模型名称；example-model 是占位值，接入时换成真实值 |
| data.usage.inputTokens | 输入 Token 数：1,500 |
| data.usage.outputTokens | 输出 Token 数：400 |
| data.usage.cachedInputTokens | 输入中的缓存部分：500，不应重复加到输入总数上 |

本例总 Token 为 1,500 + 400 = 1,900。需要配置匹配的模型价格，才能估算费用；没有价格不能按零费用理解。

## 3. score：对结果的评分

第三条表示：一分钟后，对 objectId 为 trace-document-001 的请求结果提交“回答满意度”评分，分值为 1。你的业务可以约定 1 表示满意、0 表示不满意，系统不会自动规定该含义。

| 参数 | 本例含义 |
| --- | --- |
| eventId | 此评分上报事件的标识 |
| type | score，表示评分 |
| objectId | 这条评分记录自身的标识 |
| revision | 第一版评分记录 |
| startedAt | 评分发生时间 |
| data.name | 评分项名称：回答满意度 |
| data.targetType | trace，表示评价一条完整请求；也可以评价 observation |
| data.targetId | 被评价记录的 objectId，本例是 trace-document-001，不是 traceId |
| data.valueType | numeric 表示数值评分；也支持 boolean、categorical |
| data.value | 评分值，类型必须与 valueType 一致 |

## 上报数量与页面展示

本例是一次 HTTP 请求，包含 3 个事件：1 条 Trace、1 条 Observation、1 条 Score。调用记录列表按 Trace 展示，因此对应一条请求记录，相关步骤和评分通过各自关联字段关联。不要将“HTTP 请求数”“事件数”“模型调用数”混为一谈。

## 复制示例时需要修改什么

- 新的一次业务请求：生成新的 eventId、objectId、traceId 和 spanId，并同步更新评分的 targetId。
- startedAt 改成实际发生时间。本文时间仅为示例，不能长期原样使用，接口有时间窗口校验。
- 重试同一事件：保持 eventId 和事件内容不变，不要生成新 ID。
- 修订已有记录：保留 objectId，增加 revision，使用新 eventId，并提交该记录的完整内容。revision 不是执行阶段或成功失败标记。
- 替换用户、客户、功能、供应商、模型、耗时和 Token 等示例值。

## 可省略字段与默认值

以下是本次接口简化后的约定，使用前应确认目标服务已部署对应版本。显式传入无效值仍会报错。

| 字段 | 省略时默认值 |
| --- | --- |
| schemaVersion | "1.0" |
| agent.serviceName | "serviceName-1" |
| agent.environment | "environment-1" |
| agent.instanceId | "instanceId-1" |
| revision | 1 |

agent 整体也可省略。eventId 要求恰好 36 个字符，不要求 UUID 的 8-4-4-4-12 格式；上面的 UUID 写法只是示例。traceId 仍要求 32 个十六进制字符，spanId 仍要求 16 个十六进制字符。
