#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
: "${BACKUP_RECIPIENT:?Set age public recipient}" "${BACKUP_REMOTE:?Set rclone private remote destination}"
command -v age >/dev/null; command -v rclone >/dev/null
mkdir -p backups
file="backups/metertrail-$(date -u +%Y%m%dT%H%M%SZ).dump.age"
docker compose exec -T postgres pg_dump -U metertrail -d metertrail --format=custom | age -r "$BACKUP_RECIPIENT" > "$file"
sha256sum "$file" > "$file.sha256"
rclone copy "$file" "$BACKUP_REMOTE" --checksum
rclone copy "$file.sha256" "$BACKUP_REMOTE" --checksum
echo "Encrypted backup copied to remote storage. Run a restore drill separately."
