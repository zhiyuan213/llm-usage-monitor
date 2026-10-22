import { minimalReceiptExample, sections } from "./protocol-reference";
export const batchIdExample: string = JSON.parse(minimalReceiptExample).batchId;
export const queryUrl = "https://metertrail.com/v1/batches/{batchId}";
export const queryMethod = "GET";
export const queryHeaderFields = sections[0].rows.filter((row) =>
  ["Host", "Authorization"].includes(row[0]),
);
export const queryHeaders = queryHeaderFields
  .map((row) => `${row[0]}: ${row[8]}`)
  .join("\n");
export const queryResult = {
  status: "completed",
  received: 1,
  accepted: 1,
  duplicates: 0,
  rejected: 0,
  errors: [],
};
export const queryRespExample = JSON.stringify(queryResult, null, 2);
export const queryStates = [
  ["pending", "Waiting or processing", "等待或正在处理"],
  [
    "completed",
    "Processing finished; inspect rejected and errors",
    "处理结束，需查看拒绝数量和错误",
  ],
  [
    "quarantined",
    "Isolated after processing errors; awaiting repair",
    "处理异常已隔离，等待修复",
  ],
  ["failed", "Marked as permanently failed", "已标记为永久失败"],
  ["deleted", "Project deleted; processing stopped", "项目已删除，停止处理"],
];

export const queryFields = [
  [
    "batchId",
    "URL path parameter from the upload receipt",
    "上报回执中的批次 ID，填写在 URL 路径中",
    "string",
    "36 characters; UUID",
    "36 字符；UUID",
    batchIdExample,
  ],
  [
    "status",
    "Current processing state",
    "查询时的处理状态",
    "string",
    "pending / completed / quarantined / failed / deleted",
    "pending / completed / quarantined / failed / deleted",
    "completed",
  ],
  [
    "received",
    "Number of events received",
    "接收到的事件数量",
    "integer",
    "1–500",
    "1–500",
    "1",
  ],
  [
    "accepted",
    "Number of accepted events",
    "接受的事件数量",
    "integer",
    "0–500",
    "0–500",
    "1",
  ],
  [
    "duplicates",
    "Number of duplicate events",
    "重复的事件数量",
    "integer",
    "0–500",
    "0–500",
    "0",
  ],
  [
    "rejected",
    "Number of rejected events",
    "拒绝的事件数量",
    "integer",
    "0–500",
    "0–500",
    "0",
  ],
  [
    "errors",
    "Errors for rejected events",
    "被拒绝事件的错误列表",
    "array<object>",
    "May be empty",
    "可为空数组",
    "[]",
  ],
  [
    "errors[].index",
    "Zero-based index in the submitted events array",
    "原上报 events 数组中的索引，从 0 开始",
    "integer",
    "0–499",
    "0–499",
    "0",
  ],
  [
    "errors[].code",
    "Event error code; see Errors and retries",
    "事件错误码，参见错误与重试",
    "string",
    "Protocol error code; no separate length limit",
    "协议错误码；无独立长度限制",
    "STALE_REVISION",
  ],
];
