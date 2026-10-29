import {it,expect} from 'vitest';
import {traceColumnWidths,observationTokens} from './trace-column-layout';
it('shares twelve columns with a complete width budget',()=>{expect(traceColumnWidths).toHaveLength(12);expect(traceColumnWidths.reduce((a,b)=>a+b,0)).toBe(100);});
it('counts input plus output without double counting cache, keeping unknown usage unknown',()=>{expect(observationTokens({data:{usage:{inputTokens:34,outputTokens:129,cachedInputTokens:20}}})).toBe(163);expect(observationTokens({data:{usage:{inputTokens:0,outputTokens:0}}})).toBe(0);expect(observationTokens({data:{usage:{inputTokens:34,outputTokens:null}}})).toBeNull();});
