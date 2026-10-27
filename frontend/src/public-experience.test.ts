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
 const pricing=await render('/pricing');for(const value of ['25,000','16,666','12,500'])expect(pricing).toContain(value);
});
it('quickstart works without repository scripts and links standalone guides',async()=>{
 const html=await render('/docs');expect(html).not.toContain('repository root');expect(html).not.toContain('scripts/send-example.mjs');
 for(const slug of ['nodejs','python','rest'])expect(html).toContain(`href="/tutorials/${slug}"`);
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
