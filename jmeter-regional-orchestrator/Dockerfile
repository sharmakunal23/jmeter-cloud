# SECURITY S-14 — base images pinned to immutable @sha256 digests (the tag is
# kept for readability). Refresh a digest deliberately with:
#   docker buildx imagetools inspect <tag>   # copy the "Digest:" value
FROM maven:3.9-eclipse-temurin-21@sha256:1bb51c5ed28b95aef2bc7b46bff6940da43747cdaf838ce4afc2081ce9403750 AS build

WORKDIR /build

COPY pom.xml ./pom.xml
RUN mvn dependency:go-offline -q 2>/dev/null || true

COPY src ./src
# api/openapi.yaml is bundled into static/ by the pom's <resources> block;
# without it Swagger UI's spec link 404s.
COPY api ./api
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre@sha256:010e0a06bd4e0184dec58626afb3ba727b42c56c91b977e2f0a9e0837e0fa3fb

LABEL org.opencontainers.image.title="jmeter-regional-orchestrator" \
      org.opencontainers.image.description="Per-cluster arm of the global orchestrator: creates worker Pods and relays worker calls"

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system app \
    && useradd --system --gid app --no-create-home --shell /sbin/nologin app
USER app

WORKDIR /app

COPY --from=build /build/target/jmeter-regional-orchestrator.jar /app/jmeter-regional-orchestrator.jar

ENTRYPOINT ["java", \
  "-Xms128m", "-Xmx256m", \
  "-XX:+UseG1GC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+UseContainerSupport", \
  "-jar", "/app/jmeter-regional-orchestrator.jar"]
