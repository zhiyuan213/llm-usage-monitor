import http from 'k6/http';
import {check} from 'k6';
import {Counter} from 'k6/metrics';
const batch=Number(__ENV.BATCH_SIZE||50),eps=Number(__ENV.EPS||1000);
const received=new Counter('received_events'),receipts=new Counter('accepted_batches'),errors=new Counter('non202');
export const options={scenarios:{ingest:{executor:'constant-arrival-rate',rate:Math.floor(eps/batch),timeUnit:'1s',duration:__ENV.DURATION||'60s',preAllocatedVUs:40,maxVUs:200}},thresholds:{checks:['rate==1'],http_req_duration:['p(95)<300'],http_req_failed:['rate<0.01'],dropped_iterations:['count==0']}};
function uuid(){return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,c=>{const r=Math.floor(Math.random()*16);return(c==='x'?r:(r&3)|8).toString(16)});}
export default function(){
 let trace=uuid().replaceAll('-','');const startedAt=new Date().toISOString();
 const events=Array.from({length:batch},(_,i)=>{
  const observation=(batch===1?__ITER:i)%2===1;if(!observation)trace=uuid().replaceAll('-','');
  return {eventId:uuid(),objectId:uuid(),type:observation?'observation':'trace',revision:1,traceId:trace,...(observation?{spanId:uuid().replaceAll('-','').slice(0,16)}:{}),startedAt,data:observation?{name:'probe generation',observationType:'generation',provider:'probe',model:'probe-model',status:'success',durationMs:100,usage:{inputTokens:100,outputTokens:20,cachedInputTokens:0}}:{name:'probe request',status:'success',durationMs:110}};
 });
 const r=http.post(__ENV.METERTRAIL_ENDPOINT+'/v1/events/batch',JSON.stringify({schemaVersion:'1.0',agent:{serviceName:'isolated-load',environment:'probe',instanceId:String(__VU)},events}),{headers:{Authorization:'Bearer '+__ENV.METERTRAIL_API_KEY,'Content-Type':'application/json'},timeout:'10s'});
 check(r,{'HTTP 202':v=>v.status===202});if(r.status===202){received.add(batch);receipts.add(1);if(__ITER===0&&__VU<=3)console.log('SAMPLE_BATCH '+r.json('batchId'));}else{errors.add(1);if(__ITER===0&&__VU<=3)console.log('ERROR_STATUS '+r.status+' '+r.body);}
}
