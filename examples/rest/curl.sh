#!/usr/bin/env sh
set -eu
: "${METERTRAIL_ENDPOINT:?Set endpoint}" "${METERTRAIL_API_KEY:?Set key}"
PYTHON="${PYTHON:-python3}"
trace_id="$("$PYTHON" -c 'import uuid; print(uuid.uuid4().hex)')"
trace_event_id="$("$PYTHON" -c 'import uuid; print(uuid.uuid4())')"
observation_event_id="$("$PYTHON" -c 'import uuid; print(uuid.uuid4())')"
started_at="$("$PYTHON" -c 'from datetime import datetime, timezone; print(datetime.now(timezone.utc).isoformat())')"
# Keep this file unchanged when retrying the same upload after a timeout.
cat > batch.json <<EOF
{
  "schemaVersion": "1.0",
  "agent": {"serviceName": "document-api", "environment": "test", "instanceId": "demo-1"},
  "events": [
    {
      "eventId": "$trace_event_id", "revision": 1, "type": "trace",
      "objectId": "trace-$trace_id", "traceId": "$trace_id", "startedAt": "$started_at",
      "data": {"name": "Document analysis", "status": "success", "durationMs": 2100,
        "userId": "demo-user", "customerId": "demo-customer", "feature": "document-analysis"}
    },
    {
      "eventId": "$observation_event_id", "revision": 1, "type": "observation",
      "objectId": "obs-$trace_id-1", "traceId": "$trace_id", "spanId": "0000000000000001",
      "startedAt": "$started_at",
      "data": {"name": "Model response", "observationType": "generation", "status": "success",
        "durationMs": 2000, "provider": "example", "model": "example-model",
        "usage": {"inputTokens": 1500, "outputTokens": 400, "cachedInputTokens": 0}}
    }
  ]
}
EOF
curl --fail-with-body --max-time 10 "$METERTRAIL_ENDPOINT/v1/events/batch" \
  -H "Authorization: Bearer $METERTRAIL_API_KEY" \
  -H 'Content-Type: application/json' \
  --data-binary @batch.json
