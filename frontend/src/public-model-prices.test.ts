import {beforeEach,expect,it,vi} from 'vitest';
import {createSSRApp} from 'vue';
import {renderToString} from '@vue/server-renderer';
import {publicPriceRate} from './public-price-display';
beforeEach(()=>{
 vi.resetModules();vi.stubGlobal('localStorage',{getItem:()=>null,setItem:()=>{}});
 vi.stubGlobal('document',{documentElement:{lang:'en'}});vi.stubGlobal('location',{search:''});
});
it('preserves tiny model prices rather than displaying two-decimal currency zeros',()=>{
 expect(publicPriceRate(0.003,'en')).toBe('0.003');
 expect(publicPriceRate('0.000000000001','en')).toBe('0.000000000001');
 expect(publicPriceRate(0,'en')).toBe('0');
});
it('explains private-project reference prices and renders searchable empty state',async()=>{
 const c=(await import('./PublicModelPrices.vue')).default;
 const html=await renderToString(createSSRApp(c,{project:'test',publicPricing:false}));
 expect(html).toContain('This table is for reference only');expect(html).toContain('All providers');
 expect(html).toContain('No matching public prices');expect(html).toContain('Search models');
 expect(html).not.toContain('Export Excel');
});

it('keeps a fixed 30-day historical date range custom instead of labeling it rolling',async()=>{const c=(await import('./DateRangeFilter.vue')).default;const html=await renderToString(createSSRApp(c,{value:{from:'2026-09-01T00:00:00Z',to:'2026-10-01T00:00:00Z'}}));expect(html).toContain('value="custom" selected');});
