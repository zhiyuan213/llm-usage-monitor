import { expect, it } from 'vitest';
import { restScenarios, scenarioSources } from './scenario-examples';
it('includes all required usage fields in every published model-call scenario',()=>{
 for(const batches of Object.values(restScenarios))for(const batch of batches)for(const event of batch as any[])
  if(event.data?.usage)expect(event.data.usage).toEqual({inputTokens:expect.any(Number),outputTokens:expect.any(Number),cachedInputTokens:0});
});
it('shows one request with one or three model steps, and sends optional scores separately',()=>{
  expect(restScenarios.single.map(b=>b.length)).toEqual([2]);
  expect(restScenarios.multiple.map(b=>b.length)).toEqual([1,1,2]);
  for(const kind of ['single','multiple']) {
    const events=restScenarios[kind].flat() as any[];
    expect(events.filter(e=>e.type==='trace')).toHaveLength(1);
    expect(new Set(events.map(e=>e.traceId)).size).toBe(1);
    expect(new Set(events.map(e=>e.objectId)).size).toBe(events.length);
    expect(events.every(e=>!('revision' in e))).toBe(true);
  }
  const score=restScenarios.score[0][0] as any;
  expect(score.type).toBe('score');
  expect(score.data.targetId).toBe(restScenarios.single[0][1].objectId);
  expect(score.data.targetId).toBe(restScenarios.multiple[0][0].objectId);
  expect(score.data.targetType).toBe('observation');
});
it('provides complete SDK source for every language and scenario, with later scores linked to an existing request',()=>{
  for(const language of ['Node.js','Python','Java']) for(const kind of ['single','multiple','score','score-trace']) {
    const code=scenarioSources[language][kind];
    expect(code).toContain('MeterTrail');expect(code).toContain('flush');expect(code).toContain(language==='Java'?'try (var sdk':'close');
    expect(code).toContain('METERTRAIL_API_KEY');
    expect(code).toContain(kind.startsWith('score')?'METERTRAIL_TRACE_ID':'trace');
  }
});


it('links a whole-request score to the Trace objectId, not traceId',()=>{ const e=restScenarios['score-trace'][0][0] as any; expect(e.data.targetType).toBe('trace'); expect(e.data.targetId).toBe(restScenarios.single[0][0].objectId); expect(e.data.targetId).not.toBe(restScenarios.single[0][0].traceId); });
