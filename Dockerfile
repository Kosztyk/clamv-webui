# Docker images use immutable multi-platform manifest digests for Java 21.
# Keep the tag for human readability; update both digests through Dependabot.
FROM maven:3.9-eclipse-temurin-21-noble@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
COPY dashboards ./dashboards
RUN mvn -B -q verify

# Alpine ships clamdtop in clamav-daemon. Keep scanner binaries in this stage.
FROM eclipse-temurin:21-jre-alpine-3.24@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474 AS clamdtop-extract
RUN apk add --no-cache clamav-daemon && \
    test -x /usr/bin/clamdtop

FROM eclipse-temurin:21-jre-alpine-3.24@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474
ARG CLAMAV_APP_VERSION=2.1.0
LABEL org.opencontainers.image.version=$CLAMAV_APP_VERSION
WORKDIR /app

COPY --from=clamdtop-extract --chown=0:0 --chmod=0555 /usr/bin/clamdtop /usr/bin/clamdtop
COPY --chown=0:0 --chmod=0444 licenses/clamav-COPYING.txt /usr/share/doc/clamav/COPYING.txt
# Refresh inherited security-sensitive packages from Alpine's stable repositories.
# GnuPG is used to verify Temurin's JRE download during the upstream build.
RUN set -eux; \
    apk add --no-cache --upgrade busybox libcrypto3 libexpat libssl3 musl openssl \
       clamav-libs curl ncurses-terminfo 'so:libncursesw.so.6' util-linux-misc; \
    apk del gnupg; \
    /usr/bin/clamdtop --version; \
    test -x /usr/bin/script; \
    test ! -e /usr/sbin/clamd; \
    test ! -e /usr/bin/freshclam

# Fixed numeric IDs work with Kubernetes runAsNonRoot admission checks.
RUN addgroup -g 10001 clamav-web && \
    adduser -D -u 10001 -G clamav-web -s /bin/sh clamav-web && \
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
