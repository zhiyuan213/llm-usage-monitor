import {it,expect} from 'vitest';
import {traceScores,latestTraceScore,observationScore} from './trace-score';
it('shows zero without inventing a saved score',()=>{const objects=[{type:'trace',object_id:'root'}];expect(latestTraceScore(objects)).toBe(0);expect(traceScores(objects)).toEqual([]);expect(objects).toHaveLength(1);});
it('only shows scores targeted at this trace, newest first',()=>{const objects=[{type:'trace',object_id:'root'}, {type:'score',object_id:'old',started_at:'2026-10-28',data:{targetType:'trace',targetId:'root',value:2}}, {type:'score',object_id:'new',started_at:'2026-10-29',data:{targetType:'trace',targetId:'root',value:0}}, {type:'score',data:{targetType:'observation',targetId:'root',value:99}}, {type:'score',data:{targetType:'trace',targetId:'other',value:88}}];expect(traceScores(objects)).toHaveLength(2);expect(latestTraceScore(objects)).toBe(0);});

it('matches observation scores to their own target and preserves zero/boolean values',()=>{
 const objects=[{type:'score',object_id:'a',started_at:'2026-10-28',data:{targetType:'observation',targetId:'step',value:5}},{type:'score',object_id:'b',started_at:'2026-10-29',data:{targetType:'observation',targetId:'step',value:0}},{type:'score',data:{targetType:'trace',targetId:'step',value:9}},{type:'score',data:{targetType:'observation',targetId:'other',value:false}}];
 expect(observationScore(objects,'step')).toEqual({value:0,reported:true});
 expect(observationScore(objects,'other')).toEqual({value:false,reported:true});
 expect(observationScore(objects,'missing')).toEqual({value:0,reported:false});
});
