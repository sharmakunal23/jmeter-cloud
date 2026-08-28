package com.perf.regionalorchestrator.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CriticalPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/capabilities",
            "/api/v1/pods",
            "/api/v1/pods/payments-na-east-worker-1/restart",
            "/api/v1/image",
            "/api/v1/workers/payments-na-east-worker-1/api/v1/test"
    })
    void apiV1PathsAreCritical(String path) {
        assertThat(CriticalPaths.isCritical(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/swagger-ui/index.html", "/v3/api-docs", "/openapi.yaml", "/", ""})
    void nonApiPathsAreNotCritical(String path) {
        assertThat(CriticalPaths.isCritical(path)).isFalse();
    }

    @Test
    void nullPathIsNotCritical() {
        assertThat(CriticalPaths.isCritical((String) null)).isFalse();
    }

    @Test
    void pathIdsExtractThePodNameFromBothShapes() {
        assertThat(PathIds.extract("/api/v1/pods/w-1/restart")).containsEntry("podName", "w-1");
        assertThat(PathIds.extract("/api/v1/workers/w-2/api/v1/test")).containsEntry("podName", "w-2");
        assertThat(PathIds.extract("/api/v1/capabilities")).isEmpty();
    }
}
