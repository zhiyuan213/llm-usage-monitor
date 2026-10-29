import {describe,it,expect} from 'vitest';
import {requestSummary,stepWidth,pageItems,costFormula} from './request-explorer';
describe('request explorer',()=>{
 it('groups model calls without counting trace or score twice',()=>{
 const result=requestSummary([{type:'trace',data:{}},{type:'score',data:{value:5}},{type:'observation',cost:0.2,data:{observationType:'generation',model:'a',usage:{inputTokens:10,outputTokens:4}}},{type:'observation',cost:null,data:{observationType:'generation',model:'b'}},{type:'observation',data:{observationType:'tool'}}]);
 expect(result).toEqual({calls:2,priced:1,models:'a, b',input:10,output:4,cached:null,cost:0.2});
 });
 it('keeps missing usage and cost unknown',()=>expect(requestSummary([{type:'observation',cost:null,data:{observationType:'generation'}}])).toMatchObject({input:null,output:null,cached:null,cost:null}));
 it('sums cached input separately without counting trace usage',()=>{const result=requestSummary([{type:'trace',data:{usage:{cachedInputTokens:999}}},...[0,5,7].map(cached=>({type:'observation',data:{observationType:'generation',usage:{cachedInputTokens:cached}}}))]);expect(result.cached).toBe(12);expect(requestSummary([{type:'observation',data:{observationType:'generation',usage:{cachedInputTokens:0}}}]).cached).toBe(0);});
 it('caps overlapping steps and preserves zero durations',()=>{expect(stepWidth(200,100)).toBe(100);expect(stepWidth(0,100)).toBe(0);expect(stepWidth(undefined,100)).toBe(0);});
 it('paginates the filtered collection without omissions',()=>{const rows=[1,2,3,4,5,6];expect(pageItems(rows,0,5)).toEqual([1,2,3,4,5]);expect(pageItems(rows,1,5)).toEqual([6]);expect(pageItems(rows.filter(n=>n%2===0),0,5)).toEqual([2,4,6]);});
});

it('explains cached input using recorded per-call rates and preserves unknowns',()=>{
 const call={cost:'0.00545',input_rate:'2',output_rate:'8',cached_rate:'0.5',data:{usage:{inputTokens:1500,outputTokens:400,cachedInputTokens:500}}};
 expect(costFormula(call)).toBe('((1500 − 500) × 2 + 400 × 8 + 500 × 0.5) ÷ 1,000,000 = US$0.00545');
 expect(costFormula({...call,cost:null})).toBeNull();
 expect(costFormula({...call,input_rate:null})).toBeNull();
});
it('shows both cache write costs and does not add reasoning twice',()=>{
 const call={cost:'0.0035',input_rate:3,output_rate:6,cached_rate:0.5,applied_rates:{cacheWrite5m:2.5,cacheWrite1h:4},data:{usage:{inputTokens:1000,outputTokens:200,cachedInputTokens:300,cacheWrite5mTokens:100,cacheWrite1hTokens:100,reasoningTokens:100}}};
 expect(costFormula(call)).toBe('((1000 − 300 − 100 − 100) × 3 + 200 × 6 + 300 × 0.5 + 100 × 2.5 + 100 × 4) ÷ 1,000,000 = US$0.0035');
 expect(costFormula({...call,applied_rates:null})).toBeNull();
});
