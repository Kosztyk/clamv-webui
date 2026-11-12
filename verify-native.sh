#!/usr/bin/env bash
set -euo pipefail
# Run after creating the optional native-path ClamAV container.
web="${WEB_CONTAINER:-clamav-web-client}"
scanner="${CLAMD_CONTAINER:-clamav}"
docker exec "$scanner" sh -c '
 test "$(id -u clamav)" = 10001
 pids="$(pidof clamd)"
 test -n "$pids"
 for pid in $pids; do
   awk "/^Uid:/ { if (\$2 != 10001 || \$3 != 10001) exit 1; ok=1 } END {if (!ok) exit 1}" "/proc/$pid/status"
 done
'
docker inspect "$scanner" --format '{{range .Mounts}}{{println .Destination " " .RW}}{{end}}' | awk '
 $1 == "/app/data" || $1 == "/app/conf" || $1 == "/app/data/quarantine" { bad=1 }
 $1 == "/app/data/uploads" { seen=1; if ($2 != "false") bad=1 }
 END { if (!seen || bad) exit 1 }
'
ports="$(docker inspect "$scanner" --format '{{json .HostConfig.PortBindings}}')"
case "$ports" in null|'{}') ;; *) echo "ERROR: published ClamAV port" >&2; exit 1;; esac
docker exec "$scanner" sh -c 'test ! -e /app/data/bootstrap-admin-password && test ! -e /app/data/quarantine && test ! -e /app/conf/auth-settings.key'
probe=".native-pathscan-check-$$"
trap 'docker exec -u 10001:10001 "$web" rm -f "/app/data/uploads/$probe" >/dev/null 2>&1 || true' EXIT
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
echo 'PASS: daemon UID, scanner isolation, 0600 file read access (no writes).'
echo 'NEXT: run actual clean/EICAR upload and clamdtop verification.'
