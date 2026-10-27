#!/usr/bin/env bash
# Run from the repository before replacing web. Pass candidate/previous image IDs.
set -Eeuo pipefail
test -f compose.yml
mkdir -p .local/web-assets/assets
chmod 755 .local/web-assets .local/web-assets/assets
for image in "$@"; do
  container=$(sudo -n docker create "$image")
  cleanup() { sudo -n docker rm "$container" >/dev/null; }
  trap cleanup EXIT
  sudo -n docker cp "$container:/usr/share/nginx/html/assets/." .local/web-assets/assets/
  cleanup
  trap - EXIT
done
# nginx runs as UID 101; the deployment shell may use umask 077.
sudo -n find .local/web-assets -type d -exec chmod 755 {} +
sudo -n find .local/web-assets -type f -exec chmod 644 {} +
