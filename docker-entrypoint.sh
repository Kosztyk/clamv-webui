#!/bin/sh
# Prepare an owner-only Tomcat multipart spool inside the same mounted data volume.
# Never run as root; preserve UID 10001, private uploads and issue #15 isolation.
set -eu
umask 077
# Prepare the default on-volume spool. A custom Configuration Center location must
# already exist and be owner-only; the GUI verifies this before saving.
# An explicit deployment override is still supported and takes precedence.
location="${SPRING_SERVLET_MULTIPART_LOCATION:-${CLAMAV_CONTAINER_MULTIPART_DEFAULT:-}}"
if [ -n "$location" ]; then
    case "$location" in
        /*) ;;
        *) echo 'Multipart spool default/override must be absolute' >&2; exit 1 ;;
    esac
    # Do not follow a user-supplied symlink for the temporary storage directory.
    [ ! -L "$location" ] || { echo 'Multipart temp directory must not be a symlink' >&2; exit 1; }
    if [ "$location" = /app/data/tmp/multipart ]; then
        [ ! -L /app/data ] && [ ! -L /app/data/tmp ] || {
            echo 'Multipart temp parent must not be a symlink' >&2; exit 1;
        }
    fi
    mkdir -p -- "$location"
    chmod 0700 -- "$location"
fi
# Container-aware heap, leaving headroom for native libraries, threads and multipart IO.
# Set JAVA_TOOL_OPTIONS for additional JVM flags; override percent if required.
exec java -XX:MaxRAMPercentage="${JAVA_MAX_RAM_PERCENTAGE:-70.0}" -XX:+ExitOnOutOfMemoryError -jar /app/clamav-web-client.jar "$@"
