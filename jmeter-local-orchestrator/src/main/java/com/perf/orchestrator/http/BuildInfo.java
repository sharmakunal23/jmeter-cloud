package com.perf.orchestrator.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;

/**
 * Static facts about the running process — surfaced through {@code GET /api/v1/info}.
 *
 * <p>The version is read from the manifest's {@code Implementation-Version}
 * (set by the shade plugin) so the deployed JAR can answer "what am I?"
 * without bundling a separate version file. {@code host} comes from the JVM,
 * not from {@code POD_NAME} env, because /info is intended to surface the
 * actual machine the container landed on, which can differ from the pod
 * name during failover diagnostics.
 */
public record BuildInfo(
        String version,
        String javaVersion,
        String host,
        Instant startedAt) {

    public static BuildInfo detect(Instant startedAt) {
        String version = packageVersion();
        String javaVersion = System.getProperty("java.version", "unknown");
        String host = hostName();
        return new BuildInfo(version, javaVersion, host, startedAt);
    }

    private static String packageVersion() {
        // Implementation-Version comes from the shade plugin manifest; null
        // is normal in tests and IDE runs (no shaded JAR on the classpath).
        String v = BuildInfo.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
