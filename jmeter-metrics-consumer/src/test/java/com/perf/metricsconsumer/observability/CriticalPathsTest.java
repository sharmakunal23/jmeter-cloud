package com.perf.metricsconsumer.observability;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CriticalPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1", "/api/v1/ingest"})
    void apiV1PathsAreCritical(String path) {
        assertThat(CriticalPaths.isCritical(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/health", "/swagger-ui/index.html",
            "/v3/api-docs", "/openapi.yaml", "/favicon.ico", "/", ""})
    void nonApiPathsAreNotCritical(String path) {
        assertThat(CriticalPaths.isCritical(path)).isFalse();
    }
}
