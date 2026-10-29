import {describe,it,expect} from 'vitest';
import {traceObjectLabel} from './trace-object-label';
describe('trace object labels',()=>{
  it('identifies model calls by actual model even when a generic step name exists',()=>{
    expect(traceObjectLabel({type:'observation',data:{observationType:'generation',name:'模型回答',model:'deepseek-flash'}})).toBe('deepseek-flash');
  });
  it('preserves business and non-model step names with safe fallbacks',()=>{
    expect(traceObjectLabel({type:'trace',data:{name:'文档分析',model:'model'}})).toBe('文档分析');
    expect(traceObjectLabel({type:'observation',data:{observationType:'retrieval',name:'检索'}})).toBe('检索');
    expect(traceObjectLabel({object_id:'obs-1'})).toBe('obs-1');
    expect(traceObjectLabel({})).toBe('—');
  });
});
