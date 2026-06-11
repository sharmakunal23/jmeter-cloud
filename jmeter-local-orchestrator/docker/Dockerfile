# jmeter-local-orchestrator — production image.
#
# Bakes JMeter into the same image as the orchestrator's fat JAR so the
# orchestrator can launch JMeter as a child process out of the box. This
# matches the "single-worker-per-pod, JMeter as child process" execution
# model — no sidecar container, no host-mounted JMeter install.

# ── Build stage — orchestrator fat JAR ────────────────────────────────
# maven:3.9-eclipse-temurin-21 is multi-arch (amd64 + arm64). The
# -alpine tag is x86_64-only (Adoptium doesn't ship arm64 alpine
# manifests), which would break the build on Apple Silicon.
# SECURITY S-14 — base images pinned to @sha256 digests (tag kept for
# readability). Refresh with: docker buildx imagetools inspect <tag>.
# Java 21 (RELIABILITY Round 6) — Spring Boot 3 fully supports 21; gains
# improved G1 + cgroup awareness over 17.
FROM maven:3.9-eclipse-temurin-21@sha256:1bb51c5ed28b95aef2bc7b46bff6940da43747cdaf838ce4afc2081ce9403750 AS build

WORKDIR /build

# Copy pom first so Docker layer-caches dependency downloads separately
# from source. The Avro schema lives in the sibling kafka/ subsystem;
# the build context must be the repo root for this COPY to resolve.
COPY jmeter-local-orchestrator/pom.xml ./pom.xml
COPY kafka/schemas ../kafka/schemas

RUN mvn dependency:go-offline -q 2>/dev/null || true

# api/ holds the hand-curated openapi.yaml that the pom's <resources>
# block bundles into static/ at package time.
COPY jmeter-local-orchestrator/src ./src
COPY jmeter-local-orchestrator/api ./api
# Bug-fix 2026-05-10 — bake the storage-docservice profile into the
# image so DocumentServiceArtifactSource is on the classpath. Required
# for ARTIFACT_SOURCE=DOCUMENT_SERVICE (set in docker-compose) to work
# without a NoClassDefFoundError at boot. Adds ~0 KB to the fat JAR
# (the source uses only JDK HttpClient).
RUN mvn package -DskipTests -q -Pstorage-docservice

# ── JMeter download stage ─────────────────────────────────────────────
# Pulls Apache JMeter from the Apache mirror, then installs the same
# six plugins the sibling jmeter/ image bakes. Self-contained: no
# dependency on jmeter:dev existing in the local registry.
FROM eclipse-temurin:21-jdk@sha256:b9142586f9712700c6c9e07adcedfb18608b1a3a056e4001423a3354adfa9d80 AS jmeterinstall

ARG JMETER_VERSION=5.6.3
ARG PLUGINS_MANAGER_VERSION=1.10
ARG CMDRUNNER_VERSION=2.3

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN curl -sSL "https://downloads.apache.org/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz" \
        | tar -xz -C /opt/ \
    && ln -sf /opt/apache-jmeter-${JMETER_VERSION} /opt/jmeter

ENV JMETER_HOME=/opt/jmeter
ENV PATH=/opt/jmeter/bin:$PATH

# Plugin Manager + CmdRunner — installed without auto-fetching any
# plugin set. Operators that need jpgc-/bzm- plugins can either:
#   (a) bake a custom image: extend this Dockerfile with
#       `RUN ${JMETER_HOME}/bin/PluginsManagerCMD.sh install <set>`
#   (b) install at runtime via PluginsManagerCMD.sh inside the
#       running container (one-off, not idempotent across pod restarts).
# Standard JMeter (HTTPSampler, ConstantThroughputTimer, ThreadGroup,
# CSV listener, etc.) covers most test plans without plugins.
RUN curl -sSL \
        "https://search.maven.org/remotecontent?filepath=kg/apc/jmeter-plugins-manager/${PLUGINS_MANAGER_VERSION}/jmeter-plugins-manager-${PLUGINS_MANAGER_VERSION}.jar" \
        -o ${JMETER_HOME}/lib/ext/jmeter-plugins-manager.jar \
    && curl -sSL \
        "https://search.maven.org/remotecontent?filepath=kg/apc/cmdrunner/${CMDRUNNER_VERSION}/cmdrunner-${CMDRUNNER_VERSION}.jar" \
        -o ${JMETER_HOME}/lib/cmdrunner.jar \
    && java -cp ${JMETER_HOME}/lib/ext/jmeter-plugins-manager.jar \
        org.jmeterplugins.repository.PluginManagerCMDInstaller

# Bake user.properties — JTL columns must match
# jmeter-local-orchestrator/src/.../parser. Keep this file in sync
# with jmeter/user.properties.
COPY jmeter/user.properties ${JMETER_HOME}/bin/user.properties

# ── Runtime stage — JRE + JMeter + orchestrator ──────────────────────
# eclipse-temurin:21-jre is multi-arch (amd64 + arm64).
FROM eclipse-temurin:21-jre@sha256:010e0a06bd4e0184dec58626afb3ba727b42c56c91b977e2f0a9e0837e0fa3fb

LABEL org.opencontainers.image.title="jmeter-local-orchestrator" \
      org.opencontainers.image.description="HTTP-driven orchestrator: launches JMeter as a child process, tails the JTL, and streams per-second metrics to Kafka" \
      org.opencontainers.image.source="https://github.com/your-org/jmeter-local-orchestrator"

ARG JMETER_VERSION=5.6.3

# wget for the compose healthcheck (the actuator probe). Ubuntu's
# JRE base ships curl but not wget.
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

# Bring in the JMeter install + plugins.
COPY --from=jmeterinstall /opt/apache-jmeter-${JMETER_VERSION} /opt/apache-jmeter-${JMETER_VERSION}
RUN ln -sf /opt/apache-jmeter-${JMETER_VERSION} /opt/jmeter

ENV JMETER_HOME=/opt/jmeter
ENV PATH=/opt/jmeter/bin:$PATH

# Non-root user — principle of least privilege. Owns both the
# orchestrator's working directory and the JMeter install (so the
# orchestrator can write to /opt/jmeter/bin/jmeter.log if a misconfigured
# launch happens to). Symlink + real path are both chowned.
#
# BASE_DIR (/var/lib/jmeter-orchestrator) is created + chowned at image
# build time so the orchestrator can write there even when no host volume
# is bind-mounted — Phase 1 of the capacity rework provisions per-app
# pods with anonymous volumes (or none), so the dir must be writable
# out-of-the-box. The static compose orchestrator-1 used to mask this
# because its bind mount provisioned the dir at runtime.
RUN groupadd --system orchestrator \
    && useradd --system --gid orchestrator --no-create-home --shell /sbin/nologin orchestrator \
    && chown -R orchestrator:orchestrator /opt/apache-jmeter-${JMETER_VERSION} \
    && mkdir -p /var/lib/jmeter-orchestrator \
    && chown -R orchestrator:orchestrator /var/lib/jmeter-orchestrator

USER orchestrator
WORKDIR /app

COPY --from=build /build/target/jmeter-local-orchestrator-*.jar /app/jmeter-local-orchestrator.jar

# JVM flags:
#   -Xms / -Xmx           predictable heap footprint in K8s. Bumped 512m→1g
#                         (2026-05-27 reliability pass) for headroom in the
#                         JTL tailer + per-label HDRHistogram aggregator + the
#                         disk-backed metrics buffer's in-flight index over a
#                         long run. Sized for the platform's real workload —
#                         12 h runs at 200-250 rps per worker — where a single
#                         ExitOnOutOfMemoryError at hour 10 would waste a
#                         12 h test (the worst UX). Heap is duration-bounded in
#                         steady state (the aggregator publishes+discards each
#                         second), so 1g is generous, not a per-hour creep
#                         budget. Pinned Xms==Xmx for a flat footprint.
#   G1GC + MaxGCPauseMillis  GC pauses below the 100 ms poll interval
#   ExitOnOutOfMemoryError    crash fast rather than limp on a broken heap
#                             (the worker container's mem_limit set by the
#                             global-orchestrator's PodProvisioner — default 6 GiB
#                             since RELIABILITY Round 6 — is sized to fit this 1g
#                             orchestrator heap + the 2g JMeter child + native +
#                             page cache for the multi-GB JTL. The JMeter child's
#                             own heap + native-region caps are set via
#                             JMETER_JVM_ARGS, default "-Xms2g -Xmx2g
#                             -XX:+ExitOnOutOfMemoryError -XX:MaxMetaspaceSize=256m
#                             -XX:MaxDirectMemorySize=512m
#                             -XX:ReservedCodeCacheSize=240m" — WORKER-OOM: the
#                             native caps let the JVM fail fast inside its own
#                             accounting before the shared cgroup SIGKILLs it.
#                             The child is also made the preferred OOM victim via
#                             oom_score_adj so this PID-1 orchestrator survives.)
#   UseContainerSupport       honour cgroup CPU/memory limits (default in JDK 11+)
ENTRYPOINT ["java", \
  "-Xms1g", \
  "-Xmx1g", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=100", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+UseContainerSupport", \
  "-jar", "/app/jmeter-local-orchestrator.jar"]
