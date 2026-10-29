export function traceScores(objects:any[]) {
 const root=objects.find(o=>o.type==='trace');
 if(!root)return [];
 return objects.filter(o=>o.type==='score'&&o.data?.targetType==='trace'&&o.data?.targetId===root.object_id)
  .sort((a,b)=>String(b.started_at||'').localeCompare(String(a.started_at||''))||String(a.object_id).localeCompare(String(b.object_id)));
}
export function latestTraceScore(objects:any[]) {const scores=traceScores(objects);return scores.length?scores[0].data.value:0;}
