export function refundFailureKind(row: {refund_status?:string;status?:string;provider_error_stage?:string;cancellation_status?:string;provider_http_status?:number|null}): 'auth'|'request'|'uncertain'|'legacy'|null {
  const status=row.refund_status||row.status;
  if(status==='succeeded' && (row.provider_error_stage==='submission' || !row.provider_error_stage || !['pending','unknown'].includes(row.cancellation_status||''))) return null;
  if(!['rejected','unknown','failed','canceled','succeeded'].includes(status||'')) return null;
  if(row.provider_http_status===401||row.provider_http_status===403) return 'auth';
  if(row.provider_http_status && row.provider_http_status>=400 && row.provider_http_status<500 && ![408,409,429].includes(row.provider_http_status)) return 'request';
  if(status==='rejected' && !row.provider_error_stage) return 'legacy';
  return 'uncertain';
}
