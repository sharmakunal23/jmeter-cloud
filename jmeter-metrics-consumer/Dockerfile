# JSON-INGEST (2026-07-20): the Avro toolchain is gone, so nothing is
# copied from sibling subsystems — the build context is this directory.
# SECURITY S-14 — base images pinned to @sha256 digests (tag kept for
# readability). Refresh with: docker buildx imagetools inspect <tag>.
FROM maven:3.9-eclipse-temurin-17@sha256:32ce79e40d744b18c6ce9fe65d6b58189cbabc938cfeff657a534a359a5d3f92 AS build

WORKDIR /build

COPY pom.xml ./pom.xml

RUN mvn dependency:go-offline -q 2>/dev/null || true

COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:17-jre@sha256:0d79988c68791ce864fe39d149ab1dc84f680539dca77ee7f6f3b041ad7f2f43

LABEL org.opencontainers.image.title="jmeter-metrics-consumer" \
      org.opencontainers.image.description="HTTP ingest service landing WorkerMetricBatch JSON envelopes into Postgres"

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system app \
    && useradd --system --gid app --no-create-home --shell /sbin/nologin app

WORKDIR /app

# Heap-dump destination — mount as a docker volume so dumps survive
# container restart (the next OOM should leave a smoking-gun .hprof
# the operator can analyze offline).
RUN mkdir -p /app/heapdumps && chown app:app /app/heapdumps

USER app

COPY --from=build /build/target/jmeter-metrics-consumer.jar /app/jmeter-metrics-consumer.jar

# Memory budget bumped 2026-05-16: 512m was tight when a producer cascade
# (malformed bodies on /ingest) filled the Logback async queue. 1 GB
# gives ~3x headroom; logback-spring.xml's bounded queue + the
# RateLimitedLogger cap the rate so we don't merely punt the OOM.
#
# MaxMetaspaceSize bounds class-loader leaks (rare but fatal otherwise).
# HeapDumpOnOutOfMemoryError + HeapDumpPath captures the smoking gun for
# the next OOM — /app/heapdumps is mounted as a volume in
# docker-compose.yml so the dump survives container restart.
ENTRYPOINT ["java", \
  "-Xms1g", "-Xmx1g", \
  "-XX:MaxMetaspaceSize=128m", \
  "-XX:+UseG1GC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/app/heapdumps", \
  "-XX:+UseContainerSupport", \
  "-jar", "/app/jmeter-metrics-consumer.jar"]
