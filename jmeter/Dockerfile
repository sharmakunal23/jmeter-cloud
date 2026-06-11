# jmeter-base — JMeter image with plugins pre-installed
#
# Two install modes (selected via JMETER_INSTALL_MODE build arg):
#
#   download (default, used in cloud builds)
#     Downloads apache-jmeter-${JMETER_VERSION}.tgz from the Apache mirror.
#     Reproducible. No host filesystem dependency. Slower first build.
#
#   local (used for fast dev rebuilds)
#     Copies a pre-extracted apache-jmeter-${JMETER_VERSION}/ tree from the
#     build context. Used when you already have a working JMeter installation
#     locally and want to skip the download. The directory is NOT checked
#     into git — supply it via the JMETER_DIST_PATH env var when invoking
#     buildImage.sh.
#
# Build (cloud, default download mode):
#   docker build -t jmeter-base:5.6.3 .
#
# Build (fast local, requires a local JMeter dist):
#   JMETER_DIST_PATH=/path/to/apache-jmeter-5.6.3 ./buildImage.sh
#
# Run (orchestrator launches as child process — these volumes are populated
# by the orchestrator at runtime, not by the user):
#   /testPlan   uploaded JMX
#   /dataFiles  extracted data zip
#   /results    JTL output + .done sentinel
#
# Set JMETER_PROPS env to pass -J flags: JMETER_PROPS="-Jthreads=50 -Jrampup=60"

ARG JMETER_VERSION=5.6.3
ARG PLUGINS_MANAGER_VERSION=1.10
ARG CMDRUNNER_VERSION=2.3
ARG JMETER_INSTALL_MODE=Download

# ── Stage 1: Download mode — pulls JMeter from the Apache mirror ────────────
# SECURITY S-14 — base images pinned to @sha256 digests (tag kept for
# readability). Refresh with: docker buildx imagetools inspect <tag>.
FROM eclipse-temurin:21-jdk-alpine@sha256:4fb80de7aeb277ad949cfbe89b4f504e50bb34c57fd908c5825236473d71e986 AS jmeterDownload
ARG JMETER_VERSION
RUN apk add --no-cache curl && \
    curl -sSL "https://downloads.apache.org/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz" \
        | tar -xz -C /opt/

# ── Stage 1: Local mode — copies a pre-extracted dist from the build context
FROM eclipse-temurin:21-jdk-alpine@sha256:4fb80de7aeb277ad949cfbe89b4f504e50bb34c57fd908c5825236473d71e986 AS jmeterLocal
ARG JMETER_VERSION
COPY apache-jmeter-${JMETER_VERSION}/ /opt/apache-jmeter-${JMETER_VERSION}/

# ── Stage 2: pick whichever install mode the build arg selects ──────────────
FROM jmeter${JMETER_INSTALL_MODE} AS jmeterinstall

# ── Stage 3: install plugins, bake user.properties, set up runtime layout ──
FROM jmeterinstall AS base

ARG JMETER_VERSION
ARG PLUGINS_MANAGER_VERSION
ARG CMDRUNNER_VERSION

ENV JMETER_HOME=/opt/jmeter \
    PATH=/opt/jmeter/bin:$PATH

RUN apk add --no-cache curl bash zip unzip && \
    ln -sf /opt/apache-jmeter-${JMETER_VERSION} /opt/jmeter

# Plugin Manager + CmdRunner (skipped in local mode if the dist already has them — re-download is harmless)
RUN curl -sSL \
    "https://search.maven.org/remotecontent?filepath=kg/apc/jmeter-plugins-manager/${PLUGINS_MANAGER_VERSION}/jmeter-plugins-manager-${PLUGINS_MANAGER_VERSION}.jar" \
    -o ${JMETER_HOME}/lib/ext/jmeter-plugins-manager.jar && \
    curl -sSL \
    "https://search.maven.org/remotecontent?filepath=kg/apc/cmdrunner/${CMDRUNNER_VERSION}/cmdrunner-${CMDRUNNER_VERSION}.jar" \
    -o ${JMETER_HOME}/lib/cmdrunner.jar

RUN java -cp ${JMETER_HOME}/lib/ext/jmeter-plugins-manager.jar \
    org.jmeterplugins.repository.PluginManagerCMDInstaller

# jpgc-casutg   Concurrency Thread Group — ramp/sustain/ramp-down without wall-clock juggling
# jpgc-tst      Throughput Shaping Timer  — shape RPS profile over time
# jpgc-functions Extended JMeter functions (__groovyScript, __RandomDate, etc.)
# jpgc-graphs-basic Transactions/sec and Response Times graphs for local reports
# bzm-parallel  Parallel Controller       — fire multiple child samplers simultaneously
# bzm-random-csv Random CSV Data Set      — large data files without sequential looping
RUN ${JMETER_HOME}/bin/PluginsManagerCMD.sh install \
    jpgc-casutg,\
    jpgc-tst,\
    jpgc-functions,\
    jpgc-graphs-basic,\
    bzm-parallel,\
    bzm-random-csv

COPY user.properties ${JMETER_HOME}/bin/user.properties

RUN mkdir -p /results /testPlan /dataFiles

RUN addgroup -S jmeter && adduser -S jmeter -G jmeter && \
    chown -R jmeter:jmeter ${JMETER_HOME} /results /testPlan /dataFiles

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER jmeter
WORKDIR /opt/jmeter

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["-version"]
