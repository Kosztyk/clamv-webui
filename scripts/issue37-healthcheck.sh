#!/bin/sh
# No secrets, bearer tokens, or access to private app state are required.
# When Actuator moves to a dedicated management port, probe that local listener.
set -eu
port="${MANAGEMENT_SERVER_PORT:-${SERVER_PORT:-8080}}"
case "$port" in
    ''|*[!0-9]*) echo 'Invalid liveness port' >&2; exit 1 ;;
esac
[ "$port" -ge 1 ] && [ "$port" -le 65535 ] || exit 1
exec curl --noproxy '*' --fail --silent --show-error --max-time 4 \
    "http://127.0.0.1:${port}/actuator/health/liveness" -o /dev/null
