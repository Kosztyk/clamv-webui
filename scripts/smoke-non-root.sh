#!/usr/bin/env bash
# Run against the Compose deployment after building and starting the Web UI.
set -euo pipefail
cd "$(dirname "$0")/.."

container_id="$(docker compose ps -q clamav-web-client)"
[[ -n "$container_id" ]] || { echo 'Web UI container is missing' >&2; exit 1; }
[[ "$(docker inspect --format '{{.Config.User}}' "$container_id")" == '10001:10001' ]]
[[ "$(docker inspect --format '{{.HostConfig.Privileged}}' "$container_id")" == 'false' ]]

docker compose exec -T clamav-web-client /bin/sh -eu -c '
    test "$(id -u)" = 10001
    test "$(id -g)" = 10001
    test "$(awk "/^Uid:/ {print \$2}" /proc/1/status)" = 10001
    test "$(awk "/^Gid:/ {print \$2}" /proc/1/status)" = 10001
    test "$(awk "/^NoNewPrivs:/ {print \$2}" /proc/1/status)" = 1
    test "$(awk "/^CapEff:/ {print \$2}" /proc/1/status)" = 0000000000000000
    test -r /app/clamav-web-client.jar
    test ! -w /app/clamav-web-client.jar
    test ! -w /app
    for directory in /app/conf /app/data; do
        probe="$(mktemp "$directory/.non-root-probe.XXXXXX")"
        rm "$probe"
    done
    test -x /usr/bin/clamdtop
    /usr/bin/script -q -e -c "test -t 0 && test -t 1 && test \$(id -u) = 10001 && /usr/bin/clamdtop --version" /dev/null
'
image_id="$(docker inspect --format '{{.Image}}' "$container_id")"
docker run --rm --cap-drop ALL --security-opt no-new-privileges:true \
    --mount type=volume,destination=/app/conf \
    --mount type=volume,destination=/app/data \
    --entrypoint /bin/sh "$image_id" -eu -c '
        test "$(id -u):$(id -g)" = 10001:10001
        for directory in /app/conf /app/data; do
            probe="$(mktemp "$directory/.non-root-probe.XXXXXX")"
            rm "$probe"
        done
    '
echo 'PASS: fresh Docker volumes inherit writable ownership for UID/GID 10001.'
echo 'PASS: PID 1 and exec use UID/GID 10001, no privilege escalation/capabilities, persistent mounts writable, application read-only, native PTY available.'
