import {beforeEach,expect,it,vi} from 'vitest';
import {createSSRApp} from 'vue';
import {renderToString} from '@vue/server-renderer';
beforeEach(()=>{vi.resetModules();vi.stubGlobal('localStorage',{getItem:()=>null,setItem:()=>{}});vi.stubGlobal('location',{search:''});vi.stubGlobal('document',{documentElement:{lang:''}});});
it('shows three uploads with Trace only in the final batch, not as a parent first step',async()=>{
 const c=(await import('./ScenarioFlow.vue')).default;
 const html=await renderToString(createSSRApp(c,{selected:'multiple'}));
 expect(html.match(/<li>/g)).toHaveLength(3);
 expect(html.indexOf('Observation 1')).toBeLessThan(html.indexOf('Observation 2'));
 expect(html.indexOf('Observation 2')).toBeLessThan(html.indexOf('Observation 3 + Trace'));
 expect(html).not.toContain('scenario-flow-children');
 expect(html).toContain('same batch');expect(html).toContain('actual request start time');
});
it('labels object relationship diagrams so they cannot be mistaken for upload order',async()=>{
 const c=(await import('./ScenarioFlow.vue')).default;
 const html=await renderToString(createSSRApp(c,{selected:'score-trace'}));
 expect(html).toContain('not an upload sequence');expect(html).toContain('Whole-request feedback');
});
