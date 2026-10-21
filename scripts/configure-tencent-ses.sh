#!/usr/bin/env bash
# Run on the server. Credentials are read without echo and never appear in shell history.
set +x
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
test -f .env || { echo 'Create .env first.' >&2; exit 1; }
read -r -p 'Verification template ID (approved): ' verification
read -r -p 'Notification template ID (approved): ' notification
[[ "$verification" =~ ^[1-9][0-9]*$ && "$notification" =~ ^[1-9][0-9]*$ ]] || exit 1
read -r -p 'Sender address [noreply@mail.metertrail.com]: ' sender
sender=${sender:-noreply@mail.metertrail.com}
[[ "$sender" =~ ^[a-zA-Z0-9._+-]+@[a-zA-Z0-9.-]+$ ]] || exit 1
read -r -p 'SES region [ap-hongkong]: ' region
region=${region:-ap-hongkong}
[[ "$region" == ap-hongkong || "$region" == ap-guangzhou ]] || exit 1
read -r -s -p 'SecretId (hidden): ' secret_id
printf '\n'
read -r -s -p 'SecretKey (hidden): ' secret_key
printf '\n'
[[ "$secret_id" =~ ^[a-zA-Z0-9]+$ && "$secret_key" =~ ^[a-zA-Z0-9]+$ ]] || { echo 'Invalid credential format.' >&2; exit 1; }
mkdir -p .local
temp_env=$(mktemp .local/ses-env.XXXXXX)
trap 'rm -f -- "$temp_env"; unset secret_id secret_key' EXIT
awk '!/^(MAIL_PROVIDER|MAIL_FROM|TENCENT_SES_REGION|TENCENT_SES_SECRET_ID|TENCENT_SES_SECRET_KEY|TENCENT_SES_VERIFICATION_TEMPLATE|TENCENT_SES_NOTIFICATION_TEMPLATE)=/' .env > "$temp_env"
printf '\nMAIL_PROVIDER=tencent-ses\nMAIL_FROM=%s\nTENCENT_SES_REGION=%s\nTENCENT_SES_SECRET_ID=%s\nTENCENT_SES_SECRET_KEY=%s\nTENCENT_SES_VERIFICATION_TEMPLATE=%s\nTENCENT_SES_NOTIFICATION_TEMPLATE=%s\n' \
  "$sender" "$region" "$secret_id" "$secret_key" "$verification" "$notification" >> "$temp_env"
chmod 600 "$temp_env"
mv -- "$temp_env" .env
unset secret_id secret_key
echo 'Saved .env with mode 600. No messages sent. Rebuild and recreate API/worker to apply.'
