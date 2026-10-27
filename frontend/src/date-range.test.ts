import {expect,it} from 'vitest';
import {monthDates,rollingWindow,visibleDates,customWindow} from './date-range';
it('defaults custom dates to the complete UTC month including leap years',()=>{
 expect(monthDates(new Date('2024-02-15T23:00:00Z'))).toEqual({from:'2024-02-01',to:'2024-02-29'});
 expect(monthDates(new Date('2026-12-31T12:00:00Z'))).toEqual({from:'2026-12-01',to:'2026-12-31'});
});
it('shows rolling dates without changing the exact rolling query window',()=>{
 const value=rollingWindow(7,new Date('2026-10-27T08:05:32Z'));
 expect(value).toEqual({from:'2026-10-20T08:05:32.000Z',to:'2026-10-27T08:05:32.000Z'});
 expect(visibleDates(value)).toEqual({from:'2026-10-20',to:'2026-10-27'});
 expect(visibleDates({from:'2026-09-01T00:00:00Z',to:'2026-10-01T00:00:00Z'}).to).toBe('2026-09-30');
});
it('includes the chosen final day, caps future time and rejects invalid ranges',()=>{
 const now=new Date('2026-10-27T08:00:00Z');
 expect(customWindow('2026-09-01','2026-09-30',now)).toEqual({from:'2026-09-01T00:00:00.000Z',to:'2026-10-01T00:00:00.000Z'});
 expect(customWindow('2026-10-01','2026-10-31',now)?.to).toBe(now.toISOString());
 expect(customWindow('2026-10-20','2026-10-19',now)).toBeNull();
 expect(customWindow('','',now)).toBeNull();
});
