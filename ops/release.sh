#!/usr/bin/env bash
# Run on the deployment host after reviewing CI evidence. No production auto-approval.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
: "${CANDIDATE_IMAGE:?Require repository@sha256 digest}" "${RELEASE_SHA:?Require tested commit SHA}" "${APPROVED_SHA:?Require operator-approved SHA}"
: "${SMOKE_API_KEY:?Require isolated synthetic tenant key}" "${PROMETHEUS_URL:?Require reachable monitoring URL}"
[[ "$CANDIDATE_IMAGE" =~ @sha256:[a-f0-9]{64}$ ]] || { echo 'Immutable digest required'; exit 1; }
[[ "$APPROVED_SHA" == "$RELEASE_SHA" ]] || { echo 'Approval does not match candidate'; exit 1; }
[[ "$(git rev-parse HEAD)" == "$RELEASE_SHA" ]] || { echo 'Checkout does not match candidate'; exit 1; }
mkdir -p .local/releases
evidence=".local/releases/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir "$evidence"
current=$(sed -n 's/.*server \(api-[a-z]*\):8080.*/\1/p' ops/nginx/upstream.conf)
[[ "$current" == api-blue || "$current" == api-green ]] || { echo 'Unknown active slot'; exit 1; }
target=api-green; [[ "$current" == api-green ]] && target=api-blue
old_image=$(docker inspect --format '{{.Config.Image}}' "$(docker compose ps -q "$current")")
printf '%s\n' "$RELEASE_SHA" "$CANDIDATE_IMAGE" "$old_image" > "$evidence/identity.txt"
cp ops/nginx/upstream.conf "$evidence/upstream.conf"
cp ops/prometheus/api-targets.json "$evidence/api-targets.json"
switched=false
rollback(){
  cat "$evidence/api-targets.json" > ops/prometheus/api-targets.json
  if [[ "$switched" == true ]]; then
    cat "$evidence/upstream.conf" > ops/nginx/upstream.conf
    docker compose exec -T web nginx -t && docker compose exec -T web nginx -s reload
    METERTRAIL_IMAGE="$old_image" docker compose up -d --no-deps worker
    METERTRAIL_ENDPOINT="${SMOKE_ENDPOINT:?}" METERTRAIL_API_KEY="$SMOKE_API_KEY" node scripts/release-smoke.mjs
  fi
  printf '%s rollback\n' "$(date -u +%FT%TZ)" >> "$evidence/result.txt"
}
trap 'rollback' ERR
docker pull "$CANDIDATE_IMAGE"
METERTRAIL_IMAGE="$CANDIDATE_IMAGE" docker compose --profile release up -d --no-deps --wait "$target"
# Candidate must pass the full external smoke command supplied by the operator.
# Candidate endpoint should route only to the new slot (e.g. an internal temporary listener).
: "${CANDIDATE_ENDPOINT:?Candidate endpoint required}" "${SMOKE_ENDPOINT:?Public smoke endpoint required}"
METERTRAIL_ENDPOINT="$CANDIDATE_ENDPOINT" METERTRAIL_API_KEY="$SMOKE_API_KEY" node scripts/release-smoke.mjs > "$evidence/candidate-smoke.json"
printf '[{"targets":["%s:9090","worker:9090"]}]\n' "$target" > ops/prometheus/api-targets.json
export METRICS_API_INSTANCE="$target:9090"
# Produce traffic between scrapes; a missing/NaN latency series never passes a gate.
for _ in 1 2 3; do sleep 15; METERTRAIL_ENDPOINT="$CANDIDATE_ENDPOINT" METERTRAIL_API_KEY="$SMOKE_API_KEY" node scripts/release-smoke.mjs >> "$evidence/candidate-smoke.json"; done
node scripts/monitor-gate.mjs > "$evidence/baseline.json"
printf 'upstream metertrail_api { server %s:8080; }\n' "$target" > ops/nginx/upstream.conf
docker compose exec -T web nginx -t
switched=true
docker compose exec -T web nginx -s reload
METERTRAIL_IMAGE="$CANDIDATE_IMAGE" docker compose up -d --no-deps --wait worker
METERTRAIL_ENDPOINT="$SMOKE_ENDPOINT" METERTRAIL_API_KEY="$SMOKE_API_KEY" node scripts/release-smoke.mjs > "$evidence/live-smoke.json"
for _ in $(seq 1 15); do sleep 60; node scripts/monitor-gate.mjs >> "$evidence/observation.jsonl"; done
printf '%s completed\n' "$(date -u +%FT%TZ)" >> "$evidence/result.txt"
echo "Release verified. Previous slot retained. Evidence: $evidence"
