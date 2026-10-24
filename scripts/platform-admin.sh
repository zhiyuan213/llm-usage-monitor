#!/usr/bin/env bash
# Run only as an operator on the application host. No web endpoint grants roles.
set -euo pipefail
if [ "$#" -ne 2 ] || { [ "$1" != grant ] && [ "$1" != revoke ]; }; then
  echo "Usage: bash scripts/platform-admin.sh grant|revoke existing-login-email" >&2
  exit 2
fi
cd "$(dirname "$0")/.."
sudo -n docker compose exec -T postgres psql -U metertrail -d metertrail -v ON_ERROR_STOP=1 -v mode="$1" -v email="$2" <<'SQL'
BEGIN;
CREATE TEMP TABLE admin_target ON COMMIT DROP AS
 SELECT id FROM users WHERE email=lower(:'email') AND verified AND deleted_at IS NULL;
-- Fail the whole transaction unless exactly one verified, active account exists.
SELECT 1 / count(*)::integer AS exactly_one_verified_account FROM admin_target;
SELECT id FROM users WHERE id IN (SELECT id FROM admin_target) FOR UPDATE;
UPDATE users SET is_admin=(:'mode'='grant') WHERE id IN (SELECT id FROM admin_target);
INSERT INTO audit_logs(user_id,action,target,success,user_agent)
 SELECT id,'platform-admin.' || :'mode',id::text,true,'operator-script' FROM admin_target;
SELECT email,is_admin FROM users WHERE id IN (SELECT id FROM admin_target);
COMMIT;
SQL
