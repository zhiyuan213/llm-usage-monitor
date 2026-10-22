# 数据上报错误编码

依据当前 Protocol、Support、Store、IngestController、IngestService、RateLimits 实现整理。官网开发文档的“错误与重试”同步提供中英文表格。本表针对 /v1 数据上报接口，不是全部后台管理接口的错误目录。

## 请求级错误

| HTTP | 错误码 | 含义 | 处理与重试 |
| --- | --- | --- | --- |
| 400 | `INVALID_JSON / INVALID_REQUEST` | JSON 或请求参数格式错误 | 修正请求后再提交，不原样重试 |
| 400 | `INVALID_SCHEMA` | schemaVersion 不是 1.0 | 使用协议版本 1.0 和对象形式的请求体 |
| 400 | `INVALID_BATCH_SIZE` | 事件数量不在 1–500 范围 | 拆分或补全批次 |
| 400 | `INVALID_SERVICENAME / INVALID_ENVIRONMENT / INVALID_INSTANCEID / INVALID_SDKNAME / INVALID_SDKVERSION` | agent 字段为空、类型错误或超过 128 字节 | 修正 agent；SDK 字段可省略，存在时必须有效 |
| 401 | `INVALID_API_KEY` | 密钥缺失、无效、已撤销或项目已删除 | 暂停上报，检查 Bearer 密钥及项目 |
| 404 | `BATCH_NOT_FOUND` | 该项目下找不到批次 | 检查 batchId 和密钥所属项目 |
| 413 | `PAYLOAD_TOO_LARGE` | 压缩前或解压后请求体超过 1 MiB | 拆批；单条超限则缩减事件内容 |
| 415 | `INVALID_CONTENT_TYPE / UNSUPPORTED_ENCODING` | 内容类型或压缩编码不支持 | 使用 application/json；编码可为 gzip、identity 或不设置 |
| 400 | `INVALID_GZIP` | gzip 内容无效 | 修复压缩内容后重发 |
| 429 | `RATE_LIMITED` | 请求过于频繁 | 遵循 Retry-After（当前 60 秒），加抖动并限制重试次数 |
| 429 | `QUOTA_EXCEEDED` | 额度不足、超额上限不足或付费订阅账期无效 | 暂停新增对象，检查用量、订阅和 /v1/quota；不要立即循环重试 |
| 503 | `INGEST_BACKLOG_FULL` | 待处理与隔离批次积压超过阈值 | 保留事件身份，有限次数指数退避；持续失败联系管理员 |
| 409 | `CONFLICT` | 请求与现有记录冲突 | 检查重复或冲突数据，不盲目重试 |
| 5xx / 网络 | `无固定 code / no fixed code` | 临时服务或网络错误 | 保留原 eventId、objectId、revision、时间和内容，指数退避并加抖动 |

## 逐条事件结果

202 不代表全部事件成功；在后台“最近上报批次”检查接受、重复、拒绝数量与错误。errors[].index 从 0 开始；STALE_REVISION 计为重复。

| 错误码 | 含义 | 处理与重试 |
| --- | --- | --- |
| `EVENT_TOO_LARGE` | 单个事件超过 8 KiB | 缩减事件内容 |
| `UNKNOWN_FIELD / UNKNOWN_DATA_FIELD` | 事件或 data 含未知字段 | 删除协议不支持字段，不上传完整提示词/模型回答 |
| `INVALID_EVENT_ID / INVALID_ID` | 事件 UUID 无效 | 使用有效 UUID；已有事件重试保留原 ID |
| `INVALID_TYPE / INVALID_OBJECTID / INVALID_REVISION` | 对象类型、对象 ID 或版本无效 | type 为 trace/observation/score；objectId 最多 128 字节；revision 为正整数 |
| `INVALID_TIMESTAMP / OUTSIDE_TIME_WINDOW` | 时间无效或新对象不在允许时间窗 | 使用 UTC；新对象最早 7 天前，未来最多 5 分钟 |
| `OUTSIDE_RETENTION` | 对象更新超出套餐保留期 | 检查原对象与事件时间，不反复重试过期更新 |
| `INVALID_TRACE_ID / INVALID_SPAN_ID / INVALID_PARENT_SPAN` | 链路或跨度 ID 格式不正确 | traceId 为 32 位十六进制；spanId/parentSpanId 为 16 位 |
| `INVALID_DATA / INVALID_NAME / INVALID_TARGETID` | data 或名称/目标 ID 不符合要求 | 按对象类型提供必填字段、正确类型及长度 |
| `INVALID_STATUS / INVALID_DURATION` | 状态或耗时无效 | status 为 success/error/cancelled；耗时为非负整数 |
| `INVALID_OBSERVATION_TYPE / INVALID_PROVIDER / INVALID_MODEL` | Observation 类型或模型信息无效 | 使用允许的 observationType；generation 必填 provider、model、usage |
| `INVALID_USAGE` | Token 用量结构或数值无效 | 三个 Token 字段均需显式提供，可为 null；缓存输入不得大于已知总输入 |
| `INVALID_SCORE_TARGET / INVALID_SCORE` | 评分目标、valueType 或 value 无效 | 目标类型为 trace/observation；值匹配 numeric/boolean/categorical |
| `INVALID_USERID / INVALID_CUSTOMERID / INVALID_FEATURE / INVALID_ERROR` | 业务标识或错误描述字段无效 | 业务标识最多 128 字节，error 最多 1 KiB，非空字符串 |
| `EVENT_ID_CONFLICT` | 相同 eventId 对应不同内容 | 重试原事件时不改内容；新事件使用新 eventId |
| `REVISION_CONFLICT` | 相同对象版本对应不同内容 | 更正对象时使用更高 revision 和新 eventId |
| `STALE_REVISION` | 低于当前版本，计入 duplicates 而非 rejected | 停止重试旧版本；需要更新时提交更高 revision |
| `QUOTA_EXCEEDED` | 混合批次中部分对象因额度不足被拒绝 | 处理额度后只补发被拒绝事件，保留身份并检查结果 |

相同事件重发计入 duplicates，不一定产生错误码。quarantined / failed / deleted 为批次状态，不是成功完成。隔离批次应由管理员修复重放。后台验证码发送同样使用 RATE_LIMITED，按 Retry-After 等待，不能立即循环发送。
