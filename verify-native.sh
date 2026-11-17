#!/usr/bin/env bash
set -euo pipefail
# Run from the checked-out project root after starting the containers.
web="${WEB_CONTAINER:-clamav-web-client}"
scanner="${CLAMD_CONTAINER:-clamav}"

printf '%s\n' '== Check daemon identity =='
docker exec "$scanner" sh -c '
  test "$(id -u clamav)" = 10001
  pids="$(pidof clamd)"
  test -n "$pids"
  for pid in $pids; do
    awk "/^Uid:/ { if (\$2 != 10001 || \$3 != 10001) exit 1; ok=1 } END {if (!ok) exit 1}" "/proc/$pid/status"
  done
  echo "PASS: clamav account and clamd process run as UID 10001"
'

printf '%s\n' '== Check scanner mount isolation =='
# Exact Docker container mount destinations; reject broad app-data mount.
docker inspect "$scanner" --format '{{range .Mounts}}{{println .Destination " " .RW}}{{end}}' | awk '
  $1 == "/app/data" || $1 == "/app/conf" || $1 == "/app/data/quarantine" { bad=1 }
  $1 == "/app/data/uploads" { seen=1; if ($2 != "false") bad=1 }
  END { if (!seen || bad) { print "FAIL: scanner mounts are not restricted to read-only uploads"; exit 1 } print "PASS: read-only upload mount and no broad app data mount" }
'

ports="$(docker inspect "$scanner" --format '{{json .HostConfig.PortBindings}}')"
case "$ports" in null|'{}') echo 'PASS: clamd TCP not published on host' ;; *) echo "FAIL: scanner has published ports: $ports" >&2; exit 1;; esac

docker exec "$scanner" sh -c '
  test ! -e /app/data/bootstrap-admin-password
  test ! -e /app/data/quarantine
  test ! -e /app/conf/auth-settings.key
' && echo 'PASS: no application secrets visible in scanner container'

printf '%s\n' '== Test strictly private sample upload =='
probe=".native-pathscan-check-$$"
trap 'docker exec -u 10001:10001 "$web" rm -f "/app/data/uploads/$probe" >/dev/null 2>&1 || true' EXIT
# Use the actual web-user identity to create a file like a real upload.
docker exec -u 10001:10001 "$web" sh -c '
  test "$(stat -c %a /app/data/uploads)" = 700
  umask 077
  printf "private-upload-check" > "/app/data/uploads/$1"
  chmod 600 "/app/data/uploads/$1"
  test "$(stat -c %u:%g /app/data/uploads/$1)" = "10001:10001"
' sh "$probe"
docker exec -u 10001 "$scanner" sh -c '
  test "$(stat -c %a /app/data/uploads/$1)" = 600
  test -r "/app/data/uploads/$1"
  test ! -w "/app/data/uploads/$1"
' sh "$probe"
echo 'PASS: daemon UID can read a 0600 upload and cannot write it'
echo 'NEXT: upload a harmless file and verify successful MULTISCAN and filename in clamdtop.'
echo 'NOTE: Docker exec tests do not replace a full application EICAR/cleanup smoke test.'
