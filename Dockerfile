# Docker images use immutable multi-platform manifest digests (Ubuntu Noble/Java 21).
# Keep the tag for human readability; update both digests through Dependabot.
FROM maven:3.9-eclipse-temurin-21-noble@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
COPY dashboards ./dashboards
RUN mvn -B -q verify

# Ubuntu packages clamdtop with clamav-daemon; extract ONLY the executable.
# Do not install clamd/freshclam, their services, or signature databases in the web image.
FROM eclipse-temurin:21-jre-noble@sha256:cd35bb33859641c5374415826a5951845104093eaa401cbe9d3d9023444f1c13 AS clamdtop-extract
RUN set -eux; \
    apt-get update -o Acquire::Retries=0; \
    cd /tmp; \
    apt-get download clamav-daemon -o Acquire::Retries=0; \
    dpkg-deb --extract ./clamav-daemon_*.deb /clamdtop-extracted; \
    test -x /clamdtop-extracted/usr/bin/clamdtop; \
    test ! -e /clamdtop-extracted/usr/bin/freshclam; \
    test -s /clamdtop-extracted/usr/share/doc/clamav-daemon/copyright

FROM eclipse-temurin:21-jre-noble@sha256:cd35bb33859641c5374415826a5951845104093eaa401cbe9d3d9023444f1c13
ARG CLAMAV_APP_VERSION=2.1.0
LABEL org.opencontainers.image.version=$CLAMAV_APP_VERSION
WORKDIR /app

# Runtime: only clamdtop, the libraries its build requires, the PTY helper and
# a small curl-based HTTP probe. libclamav is installed ONLY if clamdtop links it.
COPY --from=clamdtop-extract --chown=0:0 --chmod=0555 /clamdtop-extracted/usr/bin/clamdtop /usr/bin/clamdtop
COPY --from=clamdtop-extract --chown=0:0 --chmod=0444 /clamdtop-extracted/usr/share/doc/clamav-daemon/copyright /usr/share/doc/clamav-daemon/copyright
# Update APT for CVE-2026-109518 and disable download retries during the upgrade.
RUN set -eux; \
    apt-get update -o Acquire::Retries=0; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends -o Acquire::Retries=0 \
       apt libncurses6 libtinfo6 util-linux ncurses-term curl; \
    if ldd /usr/bin/clamdtop | grep -q 'libclamav.so.12 => not found'; then \
       DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends libclamav12; \
    fi; \
    if ldd /usr/bin/clamdtop | grep -q 'libclamav.so.11 => not found'; then \
       DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends libclamav11t64; \
    fi; \
    rm -rf /var/lib/apt/lists/*; \
    test -x /usr/bin/clamdtop; \
    test -x /usr/bin/script; \
    ! ldd /usr/bin/clamdtop | grep -q 'not found'; \
    ! test -e /usr/sbin/clamd; \
    ! test -e /usr/bin/freshclam

# Fixed numeric IDs work with Kubernetes runAsNonRoot admission checks.
RUN groupadd --gid 10001 clamav-web && \
    useradd --uid 10001 --gid 10001 --create-home --no-log-init \
        --shell /bin/sh clamav-web && \
    install -d -o 10001 -g 10001 -m 0755 /app/data && \
    install -d -o 10001 -g 10001 -m 0700 /app/conf

COPY --from=build --chown=0:0 --chmod=0444 /src/target/clamav-web-client*.jar /app/clamav-web-client.jar
# Multipart spool shares filesystem with /app/data/uploads; no cross-device copy.
ENV CLAMAV_CONTAINER_MULTIPART_DEFAULT=/app/data/tmp/multipart
COPY --chown=0:0 --chmod=0555 docker-entrypoint.sh /app/docker-entrypoint.sh
COPY --chown=0:0 --chmod=0555 scripts/issue37-healthcheck.sh /app/healthcheck.sh

USER 10001:10001
EXPOSE 8080
# Only Spring's minimal liveness response is anonymous; readiness/metrics remain protected.
HEALTHCHECK --interval=30s --timeout=6s --start-period=90s --retries=3 CMD ["/app/healthcheck.sh"]
ENTRYPOINT ["/app/docker-entrypoint.sh"]
