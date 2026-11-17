#!/bin/sh
# Usage: scripts/issue37-verify-and-compare.sh OLD_IMAGE NEW_IMAGE [NEW_CONTAINER]
# A Docker-enabled machine must build both images before comparing actual bytes.
set -eu
if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "Usage: $0 OLD_IMAGE NEW_IMAGE [NEW_CONTAINER]" >&2
  exit 2
fi
old_image="$1"; new_image="$2"; name="${3:-clamav-web-client}"
old_size="$(docker image inspect "$old_image" --format '{{.Size}}')"
new_size="$(docker image inspect "$new_image" --format '{{.Size}}')"
python3 - "$old_size" "$new_size" <<'PY'
import sys
old,new=map(int,sys.argv[1:])
print(f'Old image: {old:,} bytes ({old/1048576:.2f} MiB)')
print(f'New image: {new:,} bytes ({new/1048576:.2f} MiB)')
print(f'Difference: {old-new:+,} bytes ({(old-new)/1048576:+.2f} MiB, {(old-new)/old*100:+.2f}%)')
PY
cid="$(docker inspect "$name" --format '{{.Id}}')"
actual_image="$(docker inspect "$cid" --format '{{.Image}}')"
expected_image="$(docker image inspect "$new_image" --format '{{.Id}}')"
if [ "$actual_image" != "$expected_image" ]; then
  echo "FAIL: Container $name is not running the NEW_IMAGE supplied" >&2; exit 1
fi
health="$(docker inspect "$cid" --format '{{.State.Health.Status}}')"
echo "Container: $name; health: $health"
if [ "$health" != healthy ]; then
  docker inspect "$cid" --format '{{json .State.Health.Log}}'
  echo "FAIL: no healthy Docker image status" >&2; exit 1
fi
# Verify the hardening from the *running* image, not the Dockerfile.
docker exec "$cid" /bin/sh -ec '
  test -x /usr/bin/clamdtop
  test -x /usr/bin/script
  ! test -e /usr/sbin/clamd
  ! test -e /usr/bin/freshclam
  ! ldd /usr/bin/clamdtop | grep -q "not found"
  curl --noproxy "*" -fsS --max-time 4 "http://127.0.0.1:${MANAGEMENT_SERVER_PORT:-${SERVER_PORT:-8080}}/actuator/health/liveness" | grep -q '"status":"UP"'
'
echo 'PASS: healthy, minimal unauthenticated liveness, native clamdtop functional dependencies, no clamd/freshclam executable'
