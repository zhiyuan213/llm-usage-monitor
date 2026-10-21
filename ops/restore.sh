#!/usr/bin/env bash
set -Eeuo pipefail
: "${BACKUP_FILE:?Encrypted dump path}" "${AGE_IDENTITY:?Decryption identity path}" "${RESTORE_CONTAINER:?Isolated PostgreSQL container}" "${RESTORE_DATABASE:?Empty restore database}"
[[ "$RESTORE_DATABASE" =~ ^metertrail_restore_[a-zA-Z0-9_]+$ ]] || { echo 'Use an isolated metertrail_restore_* database'; exit 1; }
age -d -i "$AGE_IDENTITY" "$BACKUP_FILE" | docker exec -i "$RESTORE_CONTAINER" pg_restore -U metertrail -d "$RESTORE_DATABASE" --exit-on-error --no-owner
docker exec "$RESTORE_CONTAINER" psql -U metertrail -d "$RESTORE_DATABASE" -c 'SELECT count(*) AS objects FROM objects; SELECT sum(units) AS ledger_units FROM usage_ledger; SELECT sum(used_units) AS current_units FROM workspaces;'
echo 'Compare counts, balances, identities, and audit samples to the archived backup evidence before using this restoration.'
