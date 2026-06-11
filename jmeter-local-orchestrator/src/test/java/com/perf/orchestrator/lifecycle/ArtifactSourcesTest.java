package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.storage.ArtifactSource;
import com.perf.orchestrator.storage.HttpArtifactSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("ArtifactSources — backend selection + class-presence gating")
class ArtifactSourcesTest {

    @Nested
    @DisplayName("when ARTIFACT_SOURCE matches an always-present backend")
    class HappyDefault {

        @Test
        @DisplayName("HTTP_UPLOAD always returns the no-op HttpArtifactSource — works with or without optional profiles")
        void http_upload_returns_no_op_source() {
            ArtifactSource source = ArtifactSources.forConfig(configWith(Map.of(
                    "ARTIFACT_SOURCE", "HTTP_UPLOAD")));

            assertThat(source).isInstanceOf(HttpArtifactSource.class);
        }
    }

    @Nested
    @DisplayName("when ARTIFACT_SOURCE asks for a backend missing from this JAR")
    class MissingClassFailsLoud {

        @Test
        @DisplayName("ARTIFACT_SOURCE=S3 without -Pstorage-s3 fails at construction — silent fallback would mask a misbuilt JAR")
        void s3_without_profile_fails_loud() {
            // Skip self-aware when -Pstorage-s3 is active and the optional
            // class IS on the classpath — the contract this test pins is
            // "missing class fails loud", which can't be exercised once
            // the class is loadable.
            assumeFalse(classOnClasspath("com.perf.orchestrator.storage.S3ArtifactSource"),
                    "S3ArtifactSource is on the classpath (profile is active) — nothing to assert");

            assertThatThrownBy(() -> ArtifactSources.forConfig(configWith(Map.of(
                    "ARTIFACT_SOURCE", "S3"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("S3ArtifactSource")
                    .hasMessageContaining("-Pstorage-s3");
        }

        @Test
        @DisplayName("ARTIFACT_SOURCE=DOCUMENT_SERVICE without -Pstorage-docservice fails the same way")
        void doc_service_without_profile_fails_loud() {
            assumeFalse(classOnClasspath("com.perf.orchestrator.storage.DocumentServiceArtifactSource"),
                    "DocumentServiceArtifactSource is on the classpath (profile is active) — nothing to assert");

            assertThatThrownBy(() -> ArtifactSources.forConfig(configWith(Map.of(
                    "ARTIFACT_SOURCE", "DOCUMENT_SERVICE"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DocumentServiceArtifactSource")
                    .hasMessageContaining("-Pstorage-docservice");
        }
    }

    private static boolean classOnClasspath(String fqcn) {
        try {
            Class.forName(fqcn);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static OrchestratorConfig configWith(Map<String, String> overrides) {
        Map<String, String> env = new HashMap<>(Map.of(
                "POD_NAME",            "jmeter-worker-0",
                "TEST_REGION",         "us-east-1",
                "RUN_ID",              "factory-test",
                "JTL_PATH",            "/results/results.jtl",
                "SENTINEL_PATH",       "/results/.done",
                "KAFKA_BROKERS",       "kafka:9092",
                "SCHEMA_REGISTRY_URL", "http://schema-registry:8081",
                "KAFKA_TOPIC",         "jmeter.metrics.perSecond"
        ));
        env.putAll(overrides);
        return OrchestratorConfig.from(env);
    }
}
