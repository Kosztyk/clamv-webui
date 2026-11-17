#!/usr/bin/env bash
# Issue #36 H2 smoke test: disposable Docker container + NEW named volume.
# Never connects to, stops, mounts, or modifies the production database.
set -Eeuo pipefail
IMAGE="${1:-clamav-web-client:local}"
command -v docker >/dev/null || { echo 'ERROR: docker is required' >&2; exit 2; }
docker image inspect "$IMAGE" >/dev/null || { echo "ERROR: image not found: $IMAGE (build it first)" >&2; exit 2; }
SUFFIX="$(date +%s)-$$"
CONTAINER="clamav-issue36-${SUFFIX}"
VOLUME="clamav-issue36-${SUFFIX}"
clean() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  if [[ "${KEEP_ISSUE36_VOLUME:-0}" != "1" ]]; then
    docker volume rm "$VOLUME" >/dev/null 2>&1 || true
  else
    echo "Retained isolated volume: $VOLUME"
  fi
}
trap clean EXIT
docker volume create "$VOLUME" >/dev/null
echo "Testing isolated H2 volume $VOLUME with image $IMAGE"
# Deliberately do NOT pass SPRING_DATASOURCE_URL: this must exercise the
# actual H2 default packaged in the image, not a corrected test-only override.
# It never mounts the production configuration or database.
docker run -d --name "$CONTAINER" --network none \
  -v "$VOLUME:/app/data" \
  -e 'SPRING_DATASOURCE_USERNAME=sa' \
  -e 'CLAMAV_HOST=127.0.0.1' \
  "$IMAGE" >/dev/null
wait_started() {
  local expected="$1" elapsed=0 count
  while (( elapsed < 180 )); do
    if [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER")" != "true" ]]; then
      echo 'ERROR: disposable Web UI exited early:' >&2
      docker logs --tail 100 "$CONTAINER" >&2; return 1
    fi
    count=$(docker logs "$CONTAINER" 2>&1 | grep -Ec 'Started [^ ]+ in [0-9]' || true)
    if (( count >= expected )); then return 0; fi
    sleep 2; elapsed=$((elapsed + 2))
  done
  echo "ERROR: application did not finish boot (expected $expected start(s))" >&2
  docker logs --tail 100 "$CONTAINER" >&2
  return 1
}
check_graceful_exit() {
  local exit_code oom
  exit_code="$(docker inspect -f '{{.State.ExitCode}}' "$CONTAINER")"
  oom="$(docker inspect -f '{{.State.OOMKilled}}' "$CONTAINER")"
  if [[ "$exit_code" == "137" || "$oom" == "true" ]]; then
    echo "ERROR: app was SIGKILLed/OOM-killed (exit=$exit_code, OOM=$oom), not a verified graceful shutdown" >&2
    docker logs --tail 100 "$CONTAINER" >&2
    return 1
  fi
}
check_stopped_volume() {
  docker run --rm --network none -v "$VOLUME:/app/data:ro" --entrypoint /bin/sh "$IMAGE" -c '
    test -s /app/data/clamav-web-client.mv.db || { echo "FAIL: missing H2 data"; exit 1; }
    locks=$(find /app/data -type f -name "*.lock.db" -print)
    test -z "$locks" || { echo "FAIL: H2 lock file(s) remain: $locks"; exit 1; }
  '
}
wait_started 1
docker exec "$CONTAINER" test -s /app/data/clamav-web-client.mv.db

echo 'First graceful stop (60s timeout) ...'
docker stop -t 60 "$CONTAINER" >/dev/null
check_graceful_exit
check_stopped_volume
echo 'PASS: H2 database persists and no lock remains after first stop'

# Reuse the same disposable database to check restart and second clean stop.
docker start "$CONTAINER" >/dev/null
wait_started 2
if docker logs "$CONTAINER" 2>&1 | grep -Ei 'MVStoreException|file is corrupted|database may be already in use|database was not closed correctly|database recovery failed' ; then
  echo 'ERROR: H2 emitted a database recovery/corruption/lock error' >&2; exit 1
fi
echo 'Second graceful stop (60s timeout) ...'
docker stop -t 60 "$CONTAINER" >/dev/null
check_graceful_exit
check_stopped_volume
echo 'PASS: second startup read existing H2 database; no lock after second stop'
echo 'PASS: issue #36 disposable Docker shutdown smoke test'
