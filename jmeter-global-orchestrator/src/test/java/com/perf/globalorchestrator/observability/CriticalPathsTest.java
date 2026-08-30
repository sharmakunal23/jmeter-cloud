package com.perf.globalorchestrator.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CriticalPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1",
            "/api/v1/runs",
            "/api/v1/runs/abc-123",
            "/api/v1/applications/payments",
            "/api/v1/admin/recyclePods",
            "/api/v1/applicationGroups/cps/capacity/us-east-1/pods/foo",
            // The capabilities endpoint the UI gates on.
            "/api/v1/platform/capabilities"
    })
    void apiV1PathsAreCritical(String path) {
        assertThat(CriticalPaths.isCritical(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator",
            "/actuator/health",
            "/actuator/prometheus",
            "/swagger-ui/index.html",
            "/v3/api-docs",
            "/openapi.yaml",
            "/favicon.ico",
            "/webjars/swagger-ui/4.0.0/swagger-ui.css",
            "/",
            ""
    })
    void nonApiPathsAreNotCritical(String path) {
        assertThat(CriticalPaths.isCritical(path)).isFalse();
    }

    @Test
    void nullPathIsNotCritical() {
        assertThat(CriticalPaths.isCritical((String) null)).isFalse();
    }
}
