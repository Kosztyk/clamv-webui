# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Copy pom first for better caching
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

# Copy sources
COPY src ./src

# Build jar
RUN mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN mkdir -p /app/data /app/conf

# Copy jar from build stage
COPY --from=build /src/target/clamav-web-client*.jar /app/clamav-web-client.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/clamav-web-client.jar"]
