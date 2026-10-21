#!/usr/bin/env sh
set -eu
: "${METERTRAIL_ENDPOINT:?Set endpoint}" "${METERTRAIL_API_KEY:?Set key}"
curl --fail-with-body "$METERTRAIL_ENDPOINT/v1/events/batch" -H "Authorization: Bearer $METERTRAIL_API_KEY" -H 'Content-Type: application/json' --data @examples/batch.json
