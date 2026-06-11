# Multi-stage build — Maven for compile, JRE for runtime.
# eclipse-temurin (Ubuntu/glibc) for multi-arch (amd64 + arm64) support;
# the alpine variants ship x86_64 only.
# SECURITY S-14 — base images pinned to @sha256 digests (tag kept for
# readability). Refresh with: docker buildx imagetools inspect <tag>.
FROM maven:3.9-eclipse-temurin-17@sha256:32ce79e40d744b18c6ce9fe65d6b58189cbabc938cfeff657a534a359a5d3f92 AS build

WORKDIR /build

# pom.xml first so dependency download is layer-cached separately from source.
COPY pom.xml ./pom.xml
RUN mvn dependency:go-offline -q 2>/dev/null || true

COPY src ./src
# The api/ dir holds the hand-curated openapi.yaml — the pom's <resources>
# block bundles it into static/ at package time. Without it, /openapi.yaml
# returns 404 and Swagger UI's spec link is broken.
COPY api ./api
RUN mvn package -DskipTests -q

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre@sha256:0d79988c68791ce864fe39d149ab1dc84f680539dca77ee7f6f3b041ad7f2f43

LABEL org.opencontainers.image.title="document-service" \
      org.opencontainers.image.description="HTTP gateway abstracting blob storage for jmeter-cloud"

# wget for the actuator-driven Compose healthcheck.
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system app \
    && useradd --system --gid app --no-create-home --shell /sbin/nologin app
USER app

WORKDIR /app

COPY --from=build /build/target/document-service.jar /app/document-service.jar

ENTRYPOINT ["java", \
  "-Xms256m", "-Xmx256m", \
  "-XX:+UseG1GC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+UseContainerSupport", \
  "-jar", "/app/document-service.jar"]
