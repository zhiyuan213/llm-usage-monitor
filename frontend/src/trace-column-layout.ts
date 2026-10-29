// Shared parent/child geometry: expand, IDs, name/model, duration, tokens, cost, user/provider, status, time, action.
export const traceColumnWidths = [3,16,22,10,6,6,9,7,5,11,5];
export function observationTokens(o:any):number|null {
 const usage=o.data?.usage;
 return usage?.inputTokens==null||usage?.outputTokens==null ? null : Number(usage.inputTokens)+Number(usage.outputTokens);
}
