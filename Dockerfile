# syntax=docker/dockerfile:1

# ---------- build ----------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Wrapper and build scripts first: these change rarely, so the dependency
# download layer stays cached across source-only rebuilds.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src
# Tests run in CI, not in the image build: the image build has no Docker
# daemon available for Testcontainers.
RUN ./gradlew --no-daemon bootJar -x test

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Run as an unprivileged user.
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/build/libs/*.jar app.jar

USER app
EXPOSE 8080

# Container-aware heap sizing; the rest is configured through environment
# variables (see application.yml).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

# SIGTERM reaches the JVM directly so Spring's graceful shutdown runs:
# in-flight requests finish and the outbox worker stops claiming new work.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q UP || exit 1
