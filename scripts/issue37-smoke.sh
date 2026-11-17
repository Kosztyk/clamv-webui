#!/bin/sh
# On a Docker-enabled host: ./scripts/issue37-smoke.sh clamav-web-client:issue37
# Disposable volumes, no host ports, non-root container. Fails on an unhealthy image.
set -eu
if [ "$#" -ne 1 ]; then echo "Usage: $0 IMAGE" >&2; exit 2; fi
image="$1"; suffix="$$"; name="issue37-smoke-$suffix"
conf="issue37-smoke-conf-$suffix"; data="issue37-smoke-data-$suffix"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
version="$(python3 "$script_dir/check-versions.py" --print-version)"
image_version="$(docker image inspect "$image" --format '{{index .Config.Labels "org.opencontainers.image.version"}}')"
if [ "$image_version" != "$version" ]; then
    echo "FAIL: image version '$image_version' does not match POM version '$version'" >&2
    exit 1
fi
image_check_dir="$(mktemp -d)"
cleanup() {
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker volume rm "$conf" "$data" >/dev/null 2>&1 || true
    rm -rf "$image_check_dir"
}
trap cleanup EXIT HUP INT TERM
docker volume create "$conf" >/dev/null
docker volume create "$data" >/dev/null
docker run -d --rm --name "$name" --stop-timeout=60 \
    --security-opt no-new-privileges --cap-drop ALL \
    -v "$conf:/app/conf" -v "$data:/app/data" \
    -e CLAMAV_HOST=127.0.0.1 \
    "$image" >/dev/null
docker cp "$name:/app/clamav-web-client.jar" "$image_check_dir/clamav-web-client.jar"
python3 - "$image_check_dir/clamav-web-client.jar" "$version" <<'PY'
from pathlib import PurePosixPath
import sys
from zipfile import ZipFile

with ZipFile(sys.argv[1]) as jar:
    names = {PurePosixPath(entry).name for entry in jar.namelist()}
    stale = sorted(name for name in names if name in {
        'NativeClamdtopWebSocketHandler.class', 'jquery-3.6.0.min.js'
    } or name.startswith('NativeClamdtopWebSocketHandler$'))
    if stale:
        sys.exit('FAIL: stale content in the release JAR: ' + ', '.join(stale))
    properties = jar.read('BOOT-INF/classes/application.properties').decode().splitlines()
    if f'clamav.app.version={sys.argv[2]}' not in properties:
        sys.exit('FAIL: JAR application version does not match image version')
print('PASS: image version and packaged JAR version agree, legacy ClamdTop and jQuery files absent')
PY
ok=false
for i in $(seq 1 60); do
    state="$(docker inspect "$name" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}')" || break
    if [ "$state" = healthy ]; then ok=true; break; fi
    if [ "$state" = unhealthy ]; then
        docker inspect "$name" --format '{{json .State.Health.Log}}' >&2; break
    fi
    sleep 3
done
if [ "$ok" != true ]; then
    docker logs --tail 100 "$name" >&2
    echo "FAIL: Docker container never reached healthy state" >&2
    exit 1
fi
docker exec "$name" /bin/sh -ec '
    test "$(id -u)" = 10001
    test -x /usr/bin/clamdtop
    test ! -e /usr/sbin/clamd
    test ! -e /usr/bin/freshclam
    ! ldd /usr/bin/clamdtop | grep -q "not found"
    curl --noproxy "*" -fsS --max-time 4 http://127.0.0.1:8080/actuator/health/liveness | grep -q "\"status\":\"UP\""
    for path in /actuator/health /actuator/health/readiness /actuator/info; do
        code="$(curl --noproxy "*" --silent --output /dev/null --write-out "%{http_code}" "http://127.0.0.1:8080$path")"
        [ "$code" != 200 ] || { echo "FAIL: $path unexpectedly available anonymously" >&2; exit 1; }
    done
'
# Verify issue #36 still releases the H2 database lock after clean SIGTERM.
docker stop -t 60 "$name" >/dev/null
# Volumes are retained until cleanup, and the stopped container was --rm.
docker run --rm --entrypoint /bin/sh -v "$data:/data:ro" "$image" -ec \
    'test -s /data/clamav-web-client.mv.db && test ! -e /data/clamav-web-client.lock.db'
echo "PASS: Docker HEALTHCHECK, anonymous minimal liveness, protected actuator, native clamdtop dependencies, no scanner daemon, clean H2 stop"
