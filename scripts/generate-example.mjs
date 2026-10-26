import {writeFileSync} from 'node:fs';import {randomUUID,randomBytes} from 'node:crypto';
const traceId=randomBytes(16).toString('hex'), startedAt=new Date().toISOString();
writeFileSync(new URL('../examples/batch.json',import.meta.url),JSON.stringify({schemaVersion:'1.0',agent:{serviceName:'rest-example',environment:'development',instanceId:randomUUID()},events:[
  {eventId:randomUUID(),type:'trace',objectId:randomUUID(),revision:1,traceId,startedAt,data:{name:'Example request',status:'success',durationMs:120,userId:'demo-user',customerId:'demo-customer',feature:'document-analysis'}},
  {eventId:randomUUID(),type:'observation',objectId:randomUUID(),revision:1,traceId,spanId:randomBytes(8).toString('hex'),startedAt,data:{name:'Model response',observationType:'generation',status:'success',durationMs:100,provider:'example',model:'example-model',usage:{inputTokens:1500,outputTokens:400,cachedInputTokens:500}}}
]},null,2)+'\n');
