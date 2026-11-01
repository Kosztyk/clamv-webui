#!/usr/bin/env bash
# Optional deployment helper; non-root and forwarded HTTPS defaults are in the source.
set -euo pipefail
source_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd -P)"
image_tag='clamav-web-client:2.1.0-local'
forwarded_headers=''
requested_compose=''
requested_container=''
service_name='clamav-web-client'
project_name=''
existing_container=''
was_running=false
stopped_for_update=false
for argument in "$@"; do
    case "$argument" in
        --compose=*) requested_compose="${argument#*=}" ;;
        --container=*) requested_container="${argument#*=}" ;;
        --service=*) service_name="${argument#*=}" ;;
        --trusted-proxy) forwarded_headers=framework ;;
        --direct-access) forwarded_headers=none ;;
        --help)
            echo 'Usage: bash build-and-start.sh [--trusted-proxy|--direct-access] [--container=clamav-gui] [--compose=/absolute/docker-compose.yml] [--service=clamav-web-client]'
            exit 0 ;;
        *) echo "Unknown option: $argument (use --help)" >&2; exit 2 ;;
    esac
done
[[ "$service_name" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || { echo 'Invalid service name' >&2; exit 2; }
command -v docker >/dev/null || { echo 'Install Docker Engine and the Docker Compose plugin first.' >&2; exit 1; }
docker info >/dev/null
docker compose version >/dev/null

if [[ -n "$requested_container" ]]; then
    docker inspect "$requested_container" >/dev/null
    existing_container="$requested_container"
elif [[ -z "$requested_compose" ]]; then
    for candidate in clamav-gui clamav-web-client; do
        if docker inspect "$candidate" >/dev/null 2>&1; then
            existing_container="$candidate"
            break
        fi
    done
fi

compose_files=()
if [[ -n "$requested_compose" ]]; then
    [[ -f "$requested_compose" ]] || { echo "Compose file missing: $requested_compose" >&2; exit 1; }
    project_directory="$(cd "$(dirname "$requested_compose")" >/dev/null && pwd -P)"
    compose_files+=("$project_directory/$(basename "$requested_compose")")
elif [[ -n "$existing_container" ]]; then
    label() { docker inspect --format "{{index .Config.Labels \"$1\"}}" "$existing_container"; }
    project_directory="$(label com.docker.compose.project.working_dir)"
    project_name="$(label com.docker.compose.project)"
    service_name="$(label com.docker.compose.service)"
    file_list="$(label com.docker.compose.project.config_files)"
    [[ -d "$project_directory" && -n "$project_name" && "$project_name" != '<no value>' && -n "$file_list" && "$file_list" != '<no value>' ]] || {
        echo 'Existing container has no usable Compose metadata. Supply --compose=/absolute/docker-compose.yml.' >&2
        exit 1
    }
    [[ "$service_name" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || { echo 'Invalid Compose service metadata' >&2; exit 1; }
    IFS=',' read -r -a compose_files <<< "$file_list"
else
    project_directory="$source_directory"
    compose_files+=("$source_directory/docker-compose.yml")
fi

compose=(docker compose --project-directory "$project_directory")
[[ -z "$project_name" ]] || compose+=(--project-name "$project_name")
for compose_file in "${compose_files[@]}"; do
    [[ -f "$compose_file" ]] || { echo "Existing Compose file missing: $compose_file" >&2; exit 1; }
    compose+=(-f "$compose_file")
done
"${compose[@]}" config --quiet
"${compose[@]}" config --services | grep -Fxq "$service_name" || { echo "Service not found: $service_name" >&2; exit 1; }

# Validate and build before stopping an existing application.
echo "Building $image_tag from the complete source in $source_directory"
docker build --pull --no-cache --tag "$image_tag" "$source_directory"
[[ "$(docker image inspect --format '{{.Config.User}}' "$image_tag")" == '10001:10001' ]] || {
    echo 'Built image does not declare the expected non-root user.' >&2; exit 1
}

override_file="$project_directory/clamv-webui-local-image.override.yml"
override_marker='# Managed by ClamAV Web UI build-and-start.sh'
if [[ -e "$override_file" ]] && [[ "$(head -n 1 "$override_file")" != "$override_marker" ]]; then
    echo "Refusing to replace an unmanaged file: $override_file" >&2; exit 1
fi
# Preserve an earlier explicit proxy choice when updating this managed file.
if [[ -z "$forwarded_headers" && -f "$override_file" ]]; then
    forwarded_headers="$(sed -nE 's/^[[:space:]]+SERVER_FORWARD_HEADERS_STRATEGY: "(framework|none|native)"[[:space:]]*$/\1/p' "$override_file")"
fi
case "$forwarded_headers" in ''|framework|none|native) ;; *) echo 'Invalid proxy strategy in managed override' >&2; exit 1 ;; esac
cat > "$override_file" <<YAML
$override_marker
services:
  $service_name:
    image: $image_tag
    pull_policy: never
    user: "10001:10001"
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL
YAML
if [[ -n "$forwarded_headers" ]]; then
    cat >> "$override_file" <<YAML
    environment:
      SERVER_FORWARD_HEADERS_STRATEGY: "$forwarded_headers"
YAML
fi
already_included=false
for compose_file in "${compose_files[@]}"; do
    [[ "$compose_file" != "$override_file" ]] || already_included=true
done
[[ "$already_included" == true ]] || compose+=(-f "$override_file")
"${compose[@]}" config --quiet

# With an explicit Compose path, find the existing service only in that project.
if [[ -z "$existing_container" ]]; then
    existing_container="$("${compose[@]}" ps --all -q "$service_name")"
fi
if [[ -n "$existing_container" ]]; then
    [[ "$existing_container" != *$'\n'* ]] || { echo 'Multiple service containers found; use --container=NAME.' >&2; exit 1; }
    was_running="$(docker inspect --format '{{.State.Running}}' "$existing_container")"
fi
restore_on_failure() {
    status=$?
    if (( status != 0 )) && [[ "$stopped_for_update" == true && "$was_running" == true ]]; then
        echo 'Update failed. Attempting to restart the existing container.' >&2
        docker start "$existing_container" >/dev/null 2>&1 || true
    fi
    exit "$status"
}
trap restore_on_failure EXIT
if [[ "$was_running" == true ]]; then
    echo "Stopping only the existing Web UI: $existing_container"
    "${compose[@]}" stop "$service_name"
    stopped_for_update=true
fi

# Check using the non-root identity first. Java is not started by these probes.
if ! "${compose[@]}" run --rm --no-deps --entrypoint /bin/sh "$service_name" -eu -c '
    for directory in /app/conf /app/data; do
        probe="$(mktemp "$directory/.permission-check.XXXXXX")"
        rm "$probe"
    done
    for directory in /app/data/uploads /app/data/quarantine; do
        if test -e "$directory"; then test -r "$directory" && test -w "$directory" && test -x "$directory"; fi
    done
    for file in /app/conf/*.properties /app/conf/*.key; do
        if test -e "$file"; then test -r "$file"; fi
    done
    for file in /app/data/*.db; do
        if test -e "$file"; then test -r "$file" && test -w "$file"; fi
    done
' >/dev/null 2>&1; then
    # One-time migration touches only the service's actual application volumes.
    echo 'Preparing application configuration/data ownership for UID/GID 10001'
    "${compose[@]}" run --rm --no-deps --user 0:0 \
        --cap-add CHOWN --cap-add DAC_OVERRIDE --cap-add FOWNER \
        --entrypoint /bin/sh "$service_name" -eu -c '
            for directory in /app/conf /app/data; do
                mkdir -p "$directory"
                chown -hR 10001:10001 "$directory"
                chmod -R u+rwX "$directory"
            done
        '
fi
if [[ -n "$existing_container" ]]; then
    "${compose[@]}" up -d --no-deps --no-build --force-recreate "$service_name"
else
    "${compose[@]}" up -d --no-build
fi
stopped_for_update=false

echo 'Waiting for the Web UI login page...'
ready=false
for attempt in $(seq 1 60); do
    if "${compose[@]}" exec -T "$service_name" /bin/bash -c '
        exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-8080} || exit 1
        printf "GET /login HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3
        IFS= read -r -t 5 response <&3
        [[ "$response" == *" 200 "* ]]
    ' >/dev/null 2>&1; then
        ready=true; break
    fi
    sleep 2
done
if [[ "$ready" != true ]]; then
    "${compose[@]}" logs --tail=100 "$service_name"
    echo 'The login page is not ready. Review the application logs above.' >&2
    exit 1
fi
"${compose[@]}" exec -T "$service_name" /bin/sh -eu -c '
    test "$(id -u):$(id -g)" = 10001:10001
    grep -Eq "^Uid:[[:space:]]+10001[[:space:]]+10001[[:space:]]+10001[[:space:]]+10001" /proc/1/status
    test -r /app/clamav-web-client.jar
    test ! -w /app/clamav-web-client.jar
'
"${compose[@]}" ps "$service_name"
echo "Web UI is running as UID/GID 10001 with your configured ports and database."
echo "For later Compose commands, include this managed override: $override_file"
if [[ -z "$existing_container" ]]; then
    echo 'First-install administrator password:'
    "${compose[@]}" exec -T "$service_name" cat /app/data/bootstrap-admin-password || true
fi
