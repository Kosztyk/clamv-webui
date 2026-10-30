# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Copy pom first for better caching
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

# Copy sources and monitoring assets used by the verification suite.
COPY src ./src
COPY dashboards ./dashboards

# Build jar
RUN mvn -B -q verify

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN mkdir -p /app/data /app/conf

# Native interactive clamdtop requires the actual ncurses executable and a PTY helper.
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    clamav-daemon util-linux ncurses-term && rm -rf /var/lib/apt/lists/*
RUN test -x /usr/bin/clamdtop && test -x /usr/bin/script

# Copy jar from build stage
COPY --from=build /src/target/clamav-web-client*.jar /app/clamav-web-client.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/clamav-web-client.jar"]
