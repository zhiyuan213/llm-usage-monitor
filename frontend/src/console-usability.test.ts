import {beforeEach,describe,it,expect,vi} from 'vitest';
import {createSSRApp,nextTick} from 'vue';
import {renderToString} from '@vue/server-renderer';
import {readPageSize,usePageSize} from './page-size';
describe('console usability contracts',()=>{
 beforeEach(()=>{
  vi.resetModules();
  const values=new Map<string,string>();
  vi.stubGlobal('localStorage',{getItem:(key:string)=>values.get(key)||null,setItem:(key:string,value:string)=>values.set(key,value)});
  vi.stubGlobal('location',{search:''});vi.stubGlobal('document',{documentElement:{lang:''}});
 });
 it('defaults to 25, validates saved preferences and tolerates disabled storage',async()=>{
  expect(readPageSize(localStorage)).toBe(25);
  localStorage.setItem('metertrail.pageSize','7');expect(readPageSize(localStorage)).toBe(25);
  localStorage.setItem('metertrail.pageSize','50');const size=usePageSize();expect(size.value).toBe(50);
  size.value=100;await nextTick();expect(localStorage.getItem('metertrail.pageSize')).toBe('100');
  expect(readPageSize({getItem:()=>{throw Error('blocked');}})).toBe(25);
 });
 it('keeps exports for empty lists and renders singular row labels',async()=>{
  const component=(await import('./QueryControls.vue')).default;
  const empty=await renderToString(createSSRApp(component,{page:0,size:25,total:0,loading:false,exportUrl:'/export?scope=test'}));
  expect(empty).toContain('Export Excel');expect(empty).not.toContain('class="query-pages"');
  const single=await renderToString(createSSRApp(component,{page:0,size:25,total:1,loading:false}));
  expect(single).toContain('1 row');expect(single).not.toContain('1 rows');expect(single).toContain('value="25"');
 });
 it('shows custom-date apply only when both dates can be entered',async()=>{
  const component=(await import('./DateRangeFilter.vue')).default;
  const quick=await renderToString(createSSRApp(component,{}));expect(quick).not.toContain('Apply dates');
  const custom=await renderToString(createSSRApp(component,{initial:'custom'}));expect(custom).toContain('Apply dates');expect(custom.match(/type="date"/g)).toHaveLength(2);
 });
});
