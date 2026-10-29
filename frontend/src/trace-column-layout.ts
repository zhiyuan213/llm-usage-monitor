// Shared parent/child geometry: expand, IDs, name/model, duration, tokens, cost, user/provider, status, time, action.
export const traceColumnWidths = [2,15,18,9,6,6,10,8,6,13,7];
export function observationTokens(o:any):number|null {
 const usage=o.data?.usage;
 return usage?.inputTokens==null||usage?.outputTokens==null ? null : Number(usage.inputTokens)+Number(usage.outputTokens);
}
