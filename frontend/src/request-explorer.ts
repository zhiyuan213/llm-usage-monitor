export function requestSummary(objects:any[]) {
 const calls=objects.filter(o=>o.type==='observation'&&o.data.observationType==='generation');
 const sum=(key:string)=>{const reported=calls.filter(o=>o.data.usage?.[key]!=null);return reported.length?reported.reduce((s,o)=>s+Number(o.data.usage[key]),0):null;};
 const priced=calls.filter(o=>o.cost!=null);
 return {calls:calls.length,priced:priced.length,models:[...new Set(calls.map(o=>o.data.model).filter(Boolean))].join(', '),input:sum('inputTokens'),output:sum('outputTokens'),cached:sum('cachedInputTokens'),cost:priced.length?priced.reduce((s,o)=>s+Number(o.cost),0):null};
}
export function stepWidth(duration:unknown,total:unknown){return typeof duration==='number'&&typeof total==='number'&&total>0?Math.max(0,Math.min(100,duration/total*100)):0;}
export function pageItems<T>(items:T[],page:number,size:number){return items.slice(page*size,(page+1)*size);}

export function costFormula(o:any):string|null {
 const u=o.data?.usage;
 const values=[u?.inputTokens,u?.outputTokens,u?.cachedInputTokens,o.input_rate,o.output_rate,o.cached_rate];
 if(o.cost==null||values.some(v=>v==null||!Number.isFinite(Number(v))))return null;
 const [input,output,cached,ir,orr,cr]=values.map(Number);
 return `((${input} − ${cached}) × ${ir} + ${output} × ${orr} + ${cached} × ${cr}) ÷ 1,000,000 = US$${Number(o.cost).toFixed(12).replace(/0+$/,'').replace(/\.$/,'')}`;
}
