import {beforeEach,expect,it,vi} from 'vitest';
import {createSSRApp} from 'vue';
import {renderToString} from '@vue/server-renderer';
beforeEach(()=>{
  vi.resetModules();
  vi.stubGlobal('localStorage',{getItem:()=>null,setItem:()=>{}});
  vi.stubGlobal('document',{documentElement:{lang:'en'}});
  vi.stubGlobal('location',{search:''});
});
it('shows retained catalogue and last success when refresh fails',async()=>{
  const c=(await import('./ModelPricePolicy.vue')).default;
  const html=await renderToString(createSSRApp(c,{status:{status:'failed',model_count:120,succeeded_at:'2026-10-28T00:00:00Z',public_pricing:true}}));
  expect(html).toContain('previous prices retained');expect(html).toContain('120');
  expect(html).toContain('2026-10-28 00:00:00Z');expect(html).toContain('checked');
});
it('keeps private deployments opted out and distinguishes an unsynced catalogue',async()=>{
  const c=(await import('./ModelPricePolicy.vue')).default;
  const html=await renderToString(createSSRApp(c,{status:{status:'not_synced',model_count:0,public_pricing:false}}));
  expect(html).toContain('Waiting for first sync');expect(html).not.toContain(' checked');
  expect(html).toContain('Private models without a configured price stay unknown');
});
