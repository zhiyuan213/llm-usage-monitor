export type ReportWindow = {from:string;to:string};
const DAY=86400000;
export function monthDates(now=new Date()) {
 return {from:new Date(Date.UTC(now.getUTCFullYear(),now.getUTCMonth(),1)).toISOString().slice(0,10),to:new Date(Date.UTC(now.getUTCFullYear(),now.getUTCMonth()+1,0)).toISOString().slice(0,10)};
}
export function rollingWindow(days:number,now=new Date()):ReportWindow {
 return {from:new Date(now.getTime()-days*DAY).toISOString(),to:now.toISOString()};
}
export function visibleDates(value:ReportWindow) {
 return {from:value.from.slice(0,10),to:new Date(Date.parse(value.to)-1).toISOString().slice(0,10)};
}
export function customWindow(from:string,to:string,now=new Date()):ReportWindow|null {
 const start=Date.parse(from+'T00:00:00Z'),through=Date.parse(to+'T00:00:00Z');
 const end=Math.min(through+DAY,now.getTime());
 if(!Number.isFinite(start)||!Number.isFinite(end)||start>=end||through<start)return null;
 return {from:new Date(start).toISOString(),to:new Date(end).toISOString()};
}
