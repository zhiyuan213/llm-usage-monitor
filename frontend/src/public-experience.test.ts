import {beforeEach,expect,it,vi} from 'vitest';
import {createSSRApp} from 'vue';
import {createRouter,createMemoryHistory} from 'vue-router';
import {renderToString} from '@vue/server-renderer';
beforeEach(()=>{
 vi.resetModules();
 const storage={getItem:()=>null,setItem:()=>{},removeItem:()=>{}};
 vi.stubGlobal('localStorage',storage);vi.stubGlobal('sessionStorage',storage);
 vi.stubGlobal('location',{search:''});
 vi.stubGlobal('document',{documentElement:{lang:''},querySelector:()=>null,createElement:()=>({}),head:{appendChild:()=>{}}});
 vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({code:'UNAUTHORIZED'}),{status:401})));
});
async function render(path:string){
 const component=(await import('./Public.vue')).default;
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:page?',component}]});
 await router.push(path);await router.isReady();
 return renderToString(createSSRApp(component).use(router));
}
it('keeps unit arithmetic on pricing and provides meaningful product navigation',async()=>{
 const home=await render('/');expect(home).not.toContain('12,500');expect(home).toContain('href="/#product"');expect(home).toContain('aria-controls="public-navigation"');expect(home).toContain('What counts as a unit?');
 expect(home).toContain('without sending prompt content to MeterTrail');
 const pricing=await render('/pricing');expect(pricing).toContain('2,100,000');expect(pricing).toContain('1 Trace + 20 Observations');expect(pricing).toContain('For high-volume apps and long-term tracking');expect(pricing).toContain('<details>');expect(pricing).not.toContain('Compliance —');for(const value of ['25,000','16,666','12,500'])expect(pricing).toContain(value);
});
it('quickstart works without repository scripts and links standalone guides',async()=>{
 const html=await render('/docs');expect(html).not.toContain('repository root');expect(html).not.toContain('scripts/send-example.mjs');
 for(const slug of ['nodejs','python','java','rest'])expect(html).toContain(`href="/tutorials/${slug}"`);
});
it('publishes factual security guidance and no fictional certification',async()=>{
 const html=await render('/security');expect(html).toContain('Security and data handling');expect(html).toContain('HttpOnly');expect(html).toContain('mailto:13428961230@163.com');expect(html).toContain('not an independent security certification');
});
it('consent stays optional and a close control explicitly declines statistics',async()=>{
 const component=(await import('./AnalyticsConsent.vue')).default;
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component}]});await router.push('/');
 const html=await renderToString(createSSRApp(component).use(router));expect(html).toContain('Close without allowing statistics');expect(html).toContain('Decline');expect(html).toContain('Allow statistics');expect(html).toContain('Statistics preferences');
 expect(fetch).not.toHaveBeenCalled();
});

it('publishes final distinct policies with the confirmed operator and seven-day refunds',async()=>{
 const terms=await render('/terms'),privacy=await render('/privacy'),refunds=await render('/refunds');
 for(const html of [terms,privacy,refunds]){expect(html).not.toContain('Deployment policy template');expect(html).toContain('13428961230@163.com');expect(html).toContain('2026-10-28');}
 expect(terms).toContain('Cancellation returns the account to Free quota');
 expect(terms).toContain('Chen Zhiyuan');expect(terms).not.toContain('(陈志远)');expect(terms).toContain('Guangdong, China');expect(terms).toContain('renew automatically');
 expect(privacy).toContain('password hashes');expect(privacy).toContain('Statistics preferences');expect(privacy).toContain('30-day renewal grace');
 expect(refunds).toContain('7 calendar days');expect(refunds).toContain('prior use alone does not disqualify');expect(refunds).toContain('original payment method');
 const {setLanguage}=await import('./lib');setLanguage('zh-CN');
 const chinese=await render('/refunds');expect(chinese).toContain('7天退款');expect(chinese).toContain('申请全额退款');
});

it('states actual common plan capabilities and separates audit retention from analytics',async()=>{
 const pricing=await render('/pricing');for(const text of ['Included across all plans','Audit Excel/PDF export and customer attribution','Project-specific model price versions','not provider invoice reconciliation'])expect(pricing).toContain(text);
 const security=await render('/security');expect(security).toContain('no fixed automatic expiry');expect(security).toContain('not a promise of permanent storage');expect(security).toContain('legacy Paddle');expect(security).toContain('not a signed DPA');
 const {setLanguage}=await import('./lib');setLanguage('zh-CN');const chinese=await render('/security');expect(chinese).toContain('没有固定自动到期清理期限');expect(chinese).toContain('不代表永久保存');
});
