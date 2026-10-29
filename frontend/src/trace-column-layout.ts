// Shared geometry: expand, name/model, event, object, duration, tokens, cost, user/provider, status, score, time, action.
export const traceColumnWidths = [2,12,15,18,6,5,8,7,5,5,11,6];
export function observationTokens(o:any):number|null {
 const usage=o.data?.usage;
 return usage?.inputTokens==null||usage?.outputTokens==null ? null : Number(usage.inputTokens)+Number(usage.outputTokens);
}
