import {randomUUID,randomBytes} from 'node:crypto';
const batch={schemaVersion:'1.0',agent:{serviceName:'node-example',environment:'development',instanceId:randomUUID()},events:[{eventId:randomUUID(),objectId:randomUUID(),type:'trace',revision:1,traceId:randomBytes(16).toString('hex'),startedAt:new Date().toISOString(),data:{name:'Example request',status:'success',durationMs:120}}]};
const headers={Authorization:`Bearer ${process.env.METERTRAIL_API_KEY}`,'Content-Type':'application/json'};
const response=await fetch(`${process.env.METERTRAIL_ENDPOINT}/v1/events/batch`,{method:'POST',headers,body:JSON.stringify(batch)});console.log(response.status,await response.json());
