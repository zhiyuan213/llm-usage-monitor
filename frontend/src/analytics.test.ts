import { beforeEach, expect, it, vi } from 'vitest';
beforeEach(() => { vi.resetModules(); vi.unstubAllGlobals(); });
it('only permits fixed public pages, never account URLs or arbitrary slugs', async () => {
  const { publicPage } = await import('./analytics');
  for (const path of ['/app', '/app/messages/private-id', '/login', '/pay', '/api/me', '/tutorials/email@example.com', '/demo?email=private']) expect(publicPage(path)).toBeNull();
  expect(publicPage('/tutorials/python')).toEqual({ page_location: 'https://metertrail.com/tutorials/python', page_title: 'Python integration tutorial | MeterTrail', page_referrer: '' });
});
it('only accepts enumerated acquisition labels, never arbitrary query contents', async () => {
  const { campaign } = await import('./analytics');
  expect(campaign('?utm_source=github&utm_medium=referral&utm_campaign=launch&email=private')).toEqual({campaign_source:'github',campaign_medium:'referral',campaign_name:'launch'});
  expect(campaign('?utm_source=person@example.com&utm_campaign=secret&checkout=private')).toEqual({});
});
it('loads no tag without consent, off production, or on a private path', async () => {
  const appendChild = vi.fn();
  vi.stubGlobal('document', { head: { appendChild } });
  vi.stubGlobal('window', { location: {hostname:'metertrail.com',search:''} });
  const storage = new Map<string,string>();
  vi.stubGlobal('localStorage', { getItem:(key:string)=>storage.get(key), setItem:(key:string,value:string)=>storage.set(key,value) });
  const a = await import('./analytics');
  a.trackPage('/demo'); expect(appendChild).not.toHaveBeenCalled();
  a.setConsent(true); a.trackPage('/app/workspace'); expect(appendChild).not.toHaveBeenCalled();
  window.location.hostname='localhost'; a.trackPage('/demo'); expect(appendChild).not.toHaveBeenCalled();
});
it('deduplicates views, sanitizes URL data and keeps advertising disabled', async () => {
  const appendChild=vi.fn(), script:any={};
  vi.stubGlobal('document',{head:{appendChild}, createElement:()=>script});
  const w:any={location:{hostname:'metertrail.com',search:'?email=private&checkout=secret&utm_source=github'}};
  vi.stubGlobal('window',w); vi.stubGlobal('localStorage',{getItem:()=> 'granted'});
  const a=await import('./analytics'); a.trackPage('/demo'); a.trackPage('/demo'); a.trackAction('open_tutorial','/demo');
  expect(appendChild).toHaveBeenCalledTimes(1);
  const commands=w.dataLayer.map((args:IArguments)=>Array.from(args));
  expect(commands.filter((c:any[])=>c[1]==='page_view')).toHaveLength(1);
  expect(commands.find((c:any[])=>c[0]==='config')[2]).toMatchObject({send_page_view:false,allow_google_signals:false,allow_ad_personalization_signals:false,campaign_source:'github'});
  expect(JSON.stringify(commands)).not.toMatch(/private|secret|email=/);
  a.trackPage('/login'); a.trackAction('start_free','/app/workspace'); expect(w.dataLayer.length).toBe(commands.length);
});
it('withdrawal stops measurement, clears only this property cookies and unloads the tag', async () => {
  const cookies:string[]=[]; const reload=vi.fn(); let choice='granted';
  const doc={head:{appendChild:vi.fn()},createElement:()=>({}),set cookie(value:string){cookies.push(value);}};
  const w:any={location:{hostname:'metertrail.com',search:'',reload}};
  vi.stubGlobal('document',doc);vi.stubGlobal('window',w);
  vi.stubGlobal('localStorage',{getItem:()=>choice,setItem:(_key:string,value:string)=>{choice=value;}});
  const a=await import('./analytics');a.trackPage('/demo');a.setConsent(false);
  const count=w.dataLayer.length;a.trackPage('/pricing');a.trackAction('open_demo','/');
  expect(w.dataLayer.length).toBe(count);expect(reload).toHaveBeenCalledOnce();
  expect(cookies).toHaveLength(6);expect(cookies.every(c=>c.startsWith('_ga=')||c.startsWith('_ga_W26TJWRR17='))).toBe(true);
  expect(Array.from(w.dataLayer.at(-1))).toEqual(['consent','update',{analytics_storage:'denied'}]);
});
