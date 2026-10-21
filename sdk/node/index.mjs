import {randomUUID} from 'node:crypto';

/** In-memory, nonblocking telemetry client. Never put prompts or secrets in data. */
export class MeterTrail {
  constructor({apiKey=process.env.METERTRAIL_API_KEY,endpoint=process.env.METERTRAIL_ENDPOINT,serviceName,environment='production',onResult=()=>{},onError=()=>{},fetch:transport=globalThis.fetch,intervalMs=5000,maxRetries=5}) {
    if(!apiKey||!endpoint||!serviceName)throw new Error('apiKey, endpoint, serviceName are required');
    const url=new URL(endpoint);if(url.protocol!=='https:'&&!['localhost','127.0.0.1'].includes(url.hostname))throw new Error('HTTPS is required outside localhost');
    this.apiKey=apiKey;this.endpoint=endpoint.replace(/\/$/,'');this.agent={serviceName,environment,instanceId:randomUUID(),sdkName:'metertrail-node',sdkVersion:'0.1.0'};
    this.onResult=onResult;this.onError=onError;this.fetch=transport;this.maxRetries=maxRetries;this.queue=[];this.bytes=0;this.paused=null;this.closed=false;this.running=null;this.pending=null;
    this.timer=setInterval(()=>{this.flush().catch(e=>this.notify({code:'NETWORK_ERROR',message:e.message}));},intervalMs);this.timer.unref?.();
  }
  notify(error){try{this.onError(error)}catch{/* callbacks must never interrupt collection */}}
  record(event){
    if(this.closed){this.notify({code:'CLOSED'});return false}
    let copy,bytes;try{copy=JSON.parse(JSON.stringify({eventId:randomUUID(),revision:1,startedAt:new Date().toISOString(),...event}));bytes=Buffer.byteLength(JSON.stringify(copy))}catch{this.notify({code:'INVALID_EVENT'});return false}
    if(bytes>8192){this.notify({code:'EVENT_TOO_LARGE',event:copy});return false}
    if(this.queue.length>=10000||this.bytes+bytes>16*1024*1024){this.notify({code:'BUFFER_OVERFLOW',event:copy});return false}
    this.queue.push({event:copy,bytes});this.bytes+=bytes;
    if(this.queue.length>=100||this.bytes>=256*1024)queueMicrotask(()=>this.flush().catch(e=>this.notify({code:'NETWORK_ERROR',message:e.message})));
    return true;
  }
  async request(path,body){for(let attempt=0;;attempt++){
    try{const response=await this.fetch(this.endpoint+path,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${this.apiKey}`,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(10000)});
      const data=await response.json().catch(()=>({code:'INVALID_RESPONSE'}));
      if(response.status===429&&data.code!=='QUOTA_EXCEEDED'&&Number(response.headers.get('Retry-After'))>0){this.retryAt=Date.now()+Number(response.headers.get('Retry-After'))*1000;return {status:response.status,data}}
      if((response.status>=500||(response.status===429&&data.code!=='QUOTA_EXCEEDED'))&&attempt<this.maxRetries){await this.sleep(Math.min(10000,Math.max(Number(response.headers.get('Retry-After')||0)*1000,250*2**attempt)+Math.random()*250));continue}
      return {status:response.status,data};
    }catch(e){if(attempt>=this.maxRetries)throw e;await this.sleep(Math.min(10000,250*2**attempt+Math.random()*250))}
  }}
  sleep(ms){return new Promise(resolve=>setTimeout(resolve,ms))}
  flush(){if(this.running)return this.running;this.running=this.drain().finally(()=>{this.running=null});return this.running}
  async drain(){if(this.paused||Date.now()<(this.retryAt||0))return;const deadline=Date.now()+30000;while((this.queue.length||this.pending)&&!this.paused&&Date.now()<deadline){
    if(this.pending){const result=await this.request('/v1/batches/'+this.pending.id);if(result.status!==200){if(result.status===401||result.status===403)this.paused=result.data.code||'INVALID_API_KEY';this.notify({code:result.data.code||'BATCH_QUERY_FAILED',batchId:this.pending.id});return}
      if(result.data.status==='pending'){await this.sleep(300);continue}
      if(result.data.status!=='completed'){this.notify({code:'BATCH_FAILED',...result.data});this.pending=null;return}
      for(const error of result.data.errors||[]){const event=this.pending.events[error.index];this.notify({...error,event});if(error.code==='QUOTA_EXCEEDED'){this.paused='QUOTA_EXCEEDED';const bytes=Buffer.byteLength(JSON.stringify(event));if(this.queue.length<10000&&this.bytes+bytes<=16*1024*1024){this.queue.push({event,bytes});this.bytes+=bytes}else this.notify({code:'BUFFER_OVERFLOW',event})}}
      try{this.onResult(result.data)}catch{}this.pending=null;continue;
    }
    const items=[];let bytes=0;for(const item of this.queue){if(items.length>=100||(items.length&&bytes+item.bytes>256*1024))break;items.push(item);bytes+=item.bytes}
    const sent=await this.send(items.map(i=>i.event));if(!sent)return;
    this.queue.splice(0,items.length);this.bytes-=bytes;
  }}
  async send(events){const r=await this.request('/v1/events/batch',{schemaVersion:'1.0',agent:this.agent,events});
    if(r.status===202){this.pending={id:r.data.batchId,events};return true}
    if(r.status===401||r.status===403||r.data.code==='QUOTA_EXCEEDED'){this.paused=r.data.code||'INVALID_API_KEY';this.notify({code:this.paused});return false}
    if(r.status===413&&events.length>1){/* reduce next batches while retaining original identities */this.notify({code:'PAYLOAD_TOO_LARGE'});for(const event of events){const single=await this.request('/v1/events/batch',{schemaVersion:'1.0',agent:this.agent,events:[event]});if(single.status!==202){this.notify({code:single.data.code,event});return false}let result;do{await this.sleep(100);result=await this.request('/v1/batches/'+single.data.batchId)}while(result.status===200&&result.data.status==='pending');if(result.status!==200)return false;try{this.onResult(result.data)}catch{}for(const e of result.data.errors||[])this.notify({...e,event})}return true}
    if(r.status>=500||r.status===429){this.notify({code:r.data.code||'RETRY_EXHAUSTED'});return false}
    this.notify({code:r.data.code||'PERMANENT_ERROR',events});return true;
  }
  resume(){this.paused=null;return this.flush()}
  async checkQuota(){return this.request('/v1/quota')}
  async close(timeoutMs=10000){clearInterval(this.timer);this.closed=true;let timeout;try{await Promise.race([this.flush(),new Promise((_,reject)=>{timeout=setTimeout(()=>reject(new Error('FLUSH_TIMEOUT')),timeoutMs)})])}finally{clearTimeout(timeout)}return {remaining:this.queue.length,pendingBatch:this.pending?.id,paused:this.paused}}
}
