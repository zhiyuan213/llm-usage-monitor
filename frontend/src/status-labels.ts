/** Only platform-owned ingestion states are translated. Event data stays raw. */
export function batchStatusLabel(
  status: string,
  t: (en: string, zh: string) => string,
): string {
  const labels: Record<string, string> = {
    pending: "待处理",
    completed: "已完成",
    quarantined: "已隔离",
    failed: "已失败",
    deleted: "已删除",
  };
  return labels[status] ? t(status, labels[status]) : status;
}
