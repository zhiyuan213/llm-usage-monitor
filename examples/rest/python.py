import json
import os
import urllib.request
import uuid
from datetime import datetime, timezone

trace_id = uuid.uuid4().hex
started_at = datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')
batch = {
    'schemaVersion': '1.0',
    'agent': {
        'serviceName': 'python-example',
        'environment': 'development',
        'instanceId': str(uuid.uuid4()),
    },
    'events': [
        {
            'eventId': str(uuid.uuid4()),
            'type': 'trace',
            'objectId': str(uuid.uuid4()),
            'revision': 1,
            'traceId': trace_id,
            'startedAt': started_at,
            'data': {
                'name': 'Example request',
                'status': 'success',
                'durationMs': 120,
                'userId': 'demo-user',
                'customerId': 'demo-customer',
                'feature': 'document-analysis',
            },
        },
        {
            'eventId': str(uuid.uuid4()), 'objectId': str(uuid.uuid4()),
            'type': 'observation', 'revision': 1, 'traceId': trace_id,
            'spanId': uuid.uuid4().hex[:16], 'startedAt': started_at,
            'data': {
                'name': 'Model response', 'observationType': 'generation',
                'status': 'success', 'durationMs': 100,
                'provider': 'example', 'model': 'example-model',
                'usage': {'inputTokens': 1500, 'outputTokens': 400, 'cachedInputTokens': 500},
            },
        },
    ],
}
request = urllib.request.Request(
    os.environ['METERTRAIL_ENDPOINT'] + '/v1/events/batch',
    data=json.dumps(batch).encode(),
    headers={
        'Authorization': 'Bearer ' + os.environ['METERTRAIL_API_KEY'],
        'Content-Type': 'application/json',
    },
)
with urllib.request.urlopen(request, timeout=10) as response:
    print(response.status, response.read().decode())
