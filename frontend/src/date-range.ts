export type ReportWindow = {from:string;to:string};
export type WindowPolicy = {live:boolean;days?:number};
export function inferWindowPolicy(value:ReportWindow,now=new Date()):WindowPolicy {
 const duration=(Date.parse(value.to)-Date.parse(value.from))/86400000;
 const live=value.to.slice(0,10)===now.toISOString().slice(0,10)&&Date.parse(value.to)<=now.getTime();
 return {live,...(live&&[1,7,30].includes(duration)?{days:duration}:{})};
}
export function refreshWindow(value:ReportWindow,policy:WindowPolicy,now=new Date()):ReportWindow {
 if(!policy.live)return value;
 return policy.days?rollingWindow(policy.days,now):{from:value.from,to:now.toISOString()};
}
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
