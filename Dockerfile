# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Copy pom first for better caching
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

# Copy sources and monitoring assets used by the verification suite.
COPY src ./src
COPY dashboards ./dashboards

# Build the JAR with the packaged forwarded-HTTPS default; no helper script is required.
RUN mvn -B -q verify

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Native interactive clamdtop requires the actual ncurses executable and a PTY helper.
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    clamav-daemon util-linux ncurses-term && rm -rf /var/lib/apt/lists/*
RUN test -x /usr/bin/clamdtop && test -x /usr/bin/script

# Fixed numeric IDs also work with Kubernetes runAsNonRoot admission checks.
# /bin/sh supports the shell command used by util-linux script for native clamdtop.
RUN groupadd --gid 10001 clamav-web && \
    useradd --uid 10001 --gid 10001 --create-home --no-log-init \
        --shell /bin/sh clamav-web && \
    install -d -o 10001 -g 10001 -m 0755 /app/data && \
    install -d -o 10001 -g 10001 -m 0700 /app/conf

# The runtime user can read the application, but cannot replace its code.
COPY --from=build --chown=0:0 --chmod=0444 /src/target/clamav-web-client*.jar /app/clamav-web-client.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/clamav-web-client.jar"]
