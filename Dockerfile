# Docker images use immutable multi-platform manifest digests for Java 21.
# Keep the tag for human readability; update both digests through Dependabot.
FROM maven:3.9-eclipse-temurin-21-noble@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
COPY dashboards ./dashboards
RUN mvn -B -q verify

# Build the patched runtime packages and native ClamdTop against the same ABI.
FROM eclipse-temurin:21-jre-alpine-3.24@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474 AS clamdtop-extract
WORKDIR /tmp/native
RUN apk add --no-cache abuild build-base openssl
COPY docker/ ./
RUN set -eux; \
    SUDO= abuild-keygen -a -i -n; \
    apk update; \
    arch="$(apk --print-arch)"; \
    packages="/tmp/release-apks/native/$arch"; \
    mkdir /tmp/openssl-apks; \
    if ! ( \
        for package in openssl libcrypto3 libssl3 openssl-dev; do \
            file="$package-3.5.9-r1.apk"; \
            wget -q -T 15 -O "/tmp/openssl-apks/$file" \
                "https://dl-cdn.alpinelinux.org/alpine/edge/main/$arch/$file" || exit 1; \
        done; \
        apk add --no-cache /tmp/openssl-apks/*.apk \
    ); then \
        openssl_recipe=https://raw.githubusercontent.com/alpinelinux/aports/5e6d18a5ba2eec060b34a671a43ee54a5e72714a/main/openssl; \
        mkdir openssl; \
        for file in APKBUILD auxv.patch openssl-fips.post-install \
            CVE-2026-88822.patch CVE-2026-88823.patch CVE-2026-95691.patch \
            CVE-2026-95692.patch CVE-2026-107496.patch; do \
            wget -q -O "openssl/$file" "$openssl_recipe/$file"; \
        done; \
        abuild -F -r -C openssl -P /tmp/release-apks; \
        cp "$packages/openssl-3.5.9-r1.apk" "$packages/libcrypto3-3.5.9-r1.apk" \
           "$packages/libssl3-3.5.9-r1.apk" "$packages/openssl-dev-3.5.9-r1.apk" /tmp/openssl-apks/; \
        apk add --no-cache /tmp/openssl-apks/*.apk; \
    fi; \
    for package in libmspack libxml2 fontconfig clamav; do \
        echo "Building $package"; \
        if ! abuild -F -r -C "$package" -P /tmp/release-apks > /tmp/native-package.log 2>&1; then \
            tail -n 100 /tmp/native-package.log >&2; \
            exit 1; \
        fi; \
        tail -n 12 /tmp/native-package.log; \
    done; \
    rm -f /tmp/native-package.log; \
    mkdir /runtime-apks; \
    cp /tmp/openssl-apks/openssl-3.5.9-r1.apk /tmp/openssl-apks/libcrypto3-3.5.9-r1.apk \
       /tmp/openssl-apks/libssl3-3.5.9-r1.apk "$packages/libmspack-0.12_alpha-r0.apk" \
       "$packages/libxml2-2.15.4-r0.apk" "$packages/libxml2-16-2.15.4-r0.apk" \
       "$packages/fontconfig-2.17.1-r2.apk" \
       "$packages/clamav-libs-1.4.6-r1.apk" /runtime-apks/; \
    cp -a /etc/apk/keys /runtime-apks/keys; \
    apk add --no-cache /runtime-apks/*.apk "$packages/clamav-1.4.6-r1.apk"; \
    test -x /usr/bin/clamdtop

FROM eclipse-temurin:21-jre-alpine-3.24@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474 AS runtime-rootfs
WORKDIR /app
COPY --from=clamdtop-extract --chown=0:0 --chmod=0555 /usr/bin/clamdtop /usr/bin/clamdtop
COPY --chown=0:0 --chmod=0444 licenses/clamav-COPYING.txt /usr/share/doc/clamav/COPYING.txt
RUN --mount=type=bind,from=clamdtop-extract,source=/runtime-apks,target=/runtime-apks \
    set -eux; \
    apk --keys-dir /runtime-apks/keys add --no-cache /runtime-apks/*.apk; \
    apk add --no-cache --upgrade busybox musl curl ncurses-terminfo 'so:libncursesw.so.6' util-linux-misc; \
    apk del gnupg coreutils; \
    apk -v info -e 'openssl>=3.5.9-r1' 'libcrypto3>=3.5.9-r1' 'libssl3>=3.5.9-r1' \
        'musl>=1.2.6-r3' 'libxml2>=2.15.4-r0' 'libxml2-16>=2.15.4-r0' 'libmspack>=0.12_alpha-r0'; \
    test ! -e /usr/lib/libexpat.so.1; \
    test -e /usr/lib/libxml2.so.16; \
    test ! -e /usr/lib/libxml2.so.2; \
    /usr/bin/clamdtop --version; \
    test -x /usr/bin/script; \
    test ! -e /usr/sbin/clamd; \
    test ! -e /usr/bin/freshclam

# Fixed numeric IDs work with Kubernetes runAsNonRoot admission checks.
RUN addgroup -g 10001 clamav-web && \
    adduser -D -u 10001 -G clamav-web -s /bin/sh clamav-web && \
    install -d -o 10001 -g 10001 -m 0755 /app/data && \
    install -d -o 10001 -g 10001 -m 0700 /app/conf

# Copy the updated filesystem, including Alpine's installed-package database.
FROM scratch
COPY --from=runtime-rootfs / /
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LANG=en_US.UTF-8 LANGUAGE=en_US:en LC_ALL=en_US.UTF-8
ARG CLAMAV_APP_VERSION=2.1.0
LABEL org.opencontainers.image.version=$CLAMAV_APP_VERSION
WORKDIR /app

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
