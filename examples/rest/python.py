import json, os, uuid, urllib.request
from datetime import datetime, timezone
batch = {'schemaVersion':'1.0','agent':{'serviceName':'python-example','environment':'development','instanceId':str(uuid.uuid4())},'events':[{'eventId':str(uuid.uuid4()),'type':'trace','objectId':str(uuid.uuid4()),'revision':1,'traceId':uuid.uuid4().hex,'startedAt':datetime.now(timezone.utc).isoformat().replace('+00:00','Z'),'data':{'name':'Example request','status':'success','durationMs':120}}]}
request = urllib.request.Request(os.environ['METERTRAIL_ENDPOINT']+'/v1/events/batch',data=json.dumps(batch).encode(),headers={'Authorization':'Bearer '+os.environ['METERTRAIL_API_KEY'],'Content-Type':'application/json'})
with urllib.request.urlopen(request,timeout=10) as response:
    print(response.status, response.read().decode())
