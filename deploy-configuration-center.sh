#!/usr/bin/env bash
# Builds the application as written in source; never patches files or injects features.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
export GIT_PAGER=cat GH_PAGER=cat PAGER=cat
command -v docker >/dev/null || { echo 'Install Docker and its Compose plugin first.' >&2; exit 1; }
docker compose version >/dev/null
docker info >/dev/null
mkdir -p conf data
image='clamav-web-client:2.1.0-local'
docker build --pull -t "$image" .
if ! docker run --rm --network none --entrypoint /bin/sh \
    --mount "type=bind,src=$PWD/conf,dst=/app/conf" \
    --mount "type=bind,src=$PWD/data,dst=/app/data" "$image" -c '
      test -w /app/conf && test -w /app/data || exit 1
      for file in /app/conf/clamav-web-client.properties /app/conf/integrations.properties /app/conf/configuration-center.properties /app/conf/configuration-center.key; do
        if test -e "$file"; then test -r "$file" && test -O "$file" || exit 1; fi
      done'; then
  cat >&2 <<'MESSAGE'
Persistent volume permissions do not match application UID/GID 10001:10001.
After reviewing ownership of these application directories, prepare them with:
  sudo chown -R 10001:10001 conf data
  sudo chmod 700 conf
Then run this deployment script again. See docs/NON_ROOT_CONTAINER.md.
MESSAGE
  exit 1
fi
docker compose -f docker-compose.configuration-center.yaml up -d
cat <<'MESSAGE'
Application started with the Configuration Center Compose example.
Open http://YOUR_SERVER:8080/setup/ directly on a fresh installation.
On a trusted HTTP connection, confirm HTTP configuration on the setup page.
Read its separate recovery credential with:
  docker exec clamav-web-client cat /app/conf/configuration-recovery-token
After setup, normal administration is at /admin/configuration-center.
Logs: docker compose -f docker-compose.configuration-center.yaml logs -f --tail=100
No reverse proxy, DNS name or certificate is required for direct HTTP setup.
See docs/CONFIGURATION_CENTER.md for deployment and optional HTTPS.
MESSAGE
