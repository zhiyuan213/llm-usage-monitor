import {MeterTrail} from '../sdk/node/index.mjs';
import {randomBytes,randomUUID} from 'node:crypto';
const traceId=randomBytes(16).toString('hex'),objectId=randomUUID();
const client=new MeterTrail({serviceName:'example-app',environment:'development',onResult:console.log,onError:console.error});
client.record({type:'trace',objectId,traceId,data:{name:'Document analysis',status:'success',durationMs:1850,userId:'demo-user',customerId:'demo-customer',feature:'document-analysis'}});
client.record({type:'observation',objectId:randomUUID(),traceId,spanId:randomBytes(8).toString('hex'),data:{observationType:'generation',provider:'example',model:'example-model',status:'success',durationMs:1600,usage:{inputTokens:1500,outputTokens:400,cachedInputTokens:500},userId:'demo-user',customerId:'demo-customer',feature:'document-analysis'}});
client.record({type:'score',objectId:randomUUID(),data:{name:'quality',targetType:'trace',targetId:objectId,valueType:'numeric',value:0.92}});
const result=await client.close(15000);if(result.remaining||result.pendingBatch||result.paused)process.exitCode=1;
