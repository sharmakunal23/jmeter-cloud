package com.perf.globalorchestrator.observability;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PathIdsTest {

    @Test
    void runsPathYieldsRunId() {
        assertThat(PathIds.extract("/api/v1/runs/run-abc-123"))
                .containsEntry(PathIds.KEY_RUN_ID, "run-abc-123")
                .hasSize(1);
    }

    @Test
    void runMembersPathYieldsRunAndWorker() {
        assertThat(PathIds.extract("/api/v1/runs/r-1/members/worker-42/logs"))
                .containsEntry(PathIds.KEY_RUN_ID, "r-1")
                .containsEntry(PathIds.KEY_WORKER_ID, "worker-42");
    }

    @Test
    void applicationCapacityPathYieldsAllThree() {
        Map<String, String> ids = PathIds.extract(
                "/api/v1/applications/payments/capacity/us-east-1/pods/payments-east-worker-1");

        assertThat(ids)
                .containsEntry(PathIds.KEY_APPLICATION_ID, "payments")
                .containsEntry(PathIds.KEY_REGION, "us-east-1")
                .containsEntry(PathIds.KEY_POD_NAME, "payments-east-worker-1");
    }

    @Test
    void emptyAndNullInputsYieldEmptyMap() {
        assertThat(PathIds.extract(null)).isEmpty();
        assertThat(PathIds.extract("")).isEmpty();
        assertThat(PathIds.extract("/")).isEmpty();
    }

    @Test
    void trailingLabelWithoutValueDoesNotEmitKey() {
        // "/runs" with no segment after — must not insert a runId entry.
        assertThat(PathIds.extract("/api/v1/runs")).isEmpty();
        assertThat(PathIds.extract("/api/v1/runs/")).isEmpty();
    }

    @Test
    void templateLiteralsAreIgnored() {
        // Pasted template strings from docs/tests must not leak into MDC.
        assertThat(PathIds.extract("/api/v1/runs/{runId}")).isEmpty();
        assertThat(PathIds.extract("/api/v1/applications/{applicationId}/capacity/{region}"))
                .isEmpty();
    }

    @Test
    void queryStringIsStripped() {
        assertThat(PathIds.extract("/api/v1/runs/r-1?ignored=true"))
                .containsEntry(PathIds.KEY_RUN_ID, "r-1");
    }

    @Test
    void unrecognisedSegmentsAreIgnored() {
        assertThat(PathIds.extract("/api/v1/unknown/whatever")).isEmpty();
    }
}
