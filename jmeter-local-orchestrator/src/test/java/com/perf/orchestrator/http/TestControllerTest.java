package com.perf.orchestrator.http;

import com.perf.orchestrator.lifecycle.CurrentRun;
import com.perf.orchestrator.lifecycle.StartTestRequest;
import com.perf.orchestrator.lifecycle.TestRunManager;
import com.perf.orchestrator.lifecycle.TestState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link TestController}.
 *
 * <p>{@link TestRunManager} is fully mocked — the lifecycle (PREPARING →
 * STARTING → RUNNING → COMPLETED transitions, the run worker thread, the
 * launcher / pipeline / upload paths) is covered by {@code TestRunManagerTest}.
 * The slice's job is the HTTP boundary: status-code mapping for
 * {@link TestRunManager.StartRejection} (delegated to {@link GlobalErrorHandler}),
 * JSON deserialization of {@link StartTestRequest}, snapshot rendering,
 * and the 404 envelopes for endpoints that find no run.
 */
@WebMvcTest(controllers = TestController.class)
// @ContextConfiguration explicit because OrchestratorBeans
// (the @SpringBootConfiguration root) lives in com.perf.orchestrator.config,
// which isn't on @WebMvcTest's upward-from-test-package search path.
@ContextConfiguration(classes = TestController.class)
@Import(GlobalErrorHandler.class)
@DisplayName("TestController — Spring MVC slice (4.4e)")
class TestControllerTest {

    @Autowired MockMvc mvc;
    @MockBean TestRunManager runManager;

    @Nested
    @DisplayName("POST /api/v1/test")
    class Post {

        @Test
        @DisplayName("returns 202 with a PREPARING snapshot when the manager accepts the request")
        void returns_202_when_accepted() throws Exception {
            CurrentRun.Snapshot snap = preparingSnapshot("happy");
            when(runManager.start(any(StartTestRequest.class))).thenReturn(snap);

            mvc.perform(post("/api/v1/test")
                            .contentType("application/json")
                            .content("{\"runId\":\"happy\",\"region\":\"us-east-1\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.runId").value("happy"))
                    .andExpect(jsonPath("$.state").value("PREPARING"))
                    .andExpect(jsonPath("$.startedAt").exists());
        }

        @Test
        @DisplayName("returns 412 NO_TEST_PLAN when the manager rejects with that code — status mapped from StartRejection.status()")
        void returns_412_when_no_plan() throws Exception {
            when(runManager.start(any(StartTestRequest.class)))
                    .thenThrow(new TestRunManager.StartRejection(
                            "NO_TEST_PLAN", 412,
                            "Upload a test plan via POST /api/v1/testPlan first."));

            mvc.perform(post("/api/v1/test")
                            .contentType("application/json")
                            .content("{\"runId\":\"r1\"}"))
                    .andExpect(status().isPreconditionFailed())
                    .andExpect(jsonPath("$.error").value("NO_TEST_PLAN"))
                    .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("returns 409 TEST_RUNNING when a run is already in flight — confirms StartRejection mapping for the conflict code")
        void returns_409_when_already_running() throws Exception {
            when(runManager.start(any(StartTestRequest.class)))
                    .thenThrow(new TestRunManager.StartRejection(
                            "TEST_RUNNING", 409,
                            "A test is already in progress."));

            mvc.perform(post("/api/v1/test")
                            .contentType("application/json")
                            .content("{\"runId\":\"second\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("TEST_RUNNING"));
        }

        @Test
        @DisplayName("returns 400 BAD_REQUEST when the body is not valid JSON — Spring's HttpMessageNotReadableException is mapped by the global advice")
        void returns_400_when_body_not_json() throws Exception {
            mvc.perform(post("/api/v1/test")
                            .contentType("application/json")
                            .content("this is not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

            // Manager must not be called when parsing fails — that would
            // mutate state on a request that never made sense.
            verify(runManager, never()).start(any());
        }

        @Test
        @DisplayName("returns 400 BAD_REQUEST when scheduledStartAt is malformed — the controller validates up-front so the run worker never sees the bad timestamp")
        void returns_400_when_schedule_malformed() throws Exception {
            mvc.perform(post("/api/v1/test")
                            .contentType("application/json")
                            .content("{\"runId\":\"r1\",\"scheduledStartAt\":\"not-a-timestamp\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("scheduledStartAt")));

            verify(runManager, never()).start(any());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/test")
    class Get {

        @Test
        @DisplayName("returns 404 NO_TEST_EXISTS when no run has ever been started")
        void returns_404_when_no_test_ever() throws Exception {
            when(runManager.snapshotIfPresent()).thenReturn(Optional.empty());

            mvc.perform(get("/api/v1/test"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NO_TEST_EXISTS"));
        }

        @Test
        @DisplayName("returns 200 with the documented snapshot fields for a completed run — exitCode, elapsedMs, uploadState all surface")
        void returns_snapshot_after_completion() throws Exception {
            CurrentRun.Snapshot snap = completedSnapshot("shape");
            when(runManager.snapshotIfPresent()).thenReturn(Optional.of(snap));

            mvc.perform(get("/api/v1/test"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.runId").value("shape"))
                    .andExpect(jsonPath("$.state").value("COMPLETED"))
                    .andExpect(jsonPath("$.exitCode").value(0))
                    .andExpect(jsonPath("$.elapsedMs").exists())
                    .andExpect(jsonPath("$.uploadState").value("SKIPPED"))
                    .andExpect(jsonPath("$.jmeterAlive").value(false));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/test and POST /api/v1/test/abort")
    class StopAndAbort {

        @Test
        @DisplayName("DELETE returns 404 NO_ACTIVE_RUN when no run is in flight — stop is for active runs only")
        void delete_returns_404_when_no_active_run() throws Exception {
            when(runManager.isRunning()).thenReturn(false);

            mvc.perform(delete("/api/v1/test"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NO_ACTIVE_RUN"));

            verify(runManager, never()).stop();
        }

        @Test
        @DisplayName("DELETE returns 202 and calls runManager.stop() when a run is active — graceful drain in flight")
        void delete_returns_202_when_active() throws Exception {
            when(runManager.isRunning()).thenReturn(true);

            mvc.perform(delete("/api/v1/test"))
                    .andExpect(status().isAccepted());

            verify(runManager).stop();
        }

        @Test
        @DisplayName("POST /test/abort returns 404 NO_ACTIVE_RUN when no run is in flight")
        void abort_returns_404_when_no_active_run() throws Exception {
            when(runManager.isRunning()).thenReturn(false);

            mvc.perform(post("/api/v1/test/abort"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NO_ACTIVE_RUN"));

            verify(runManager, never()).abort();
        }

        @Test
        @DisplayName("POST /test/abort returns 202 and calls runManager.abort() — hard kill SIGKILL")
        void abort_returns_202_and_invokes_abort() throws Exception {
            when(runManager.isRunning()).thenReturn(true);

            mvc.perform(post("/api/v1/test/abort"))
                    .andExpect(status().isAccepted());

            verify(runManager).abort();
        }

        @Test
        @DisplayName("POST /test/drain returns 404 NO_ACTIVE_RUN when no run is in flight")
        void drain_returns_404_when_no_active_run() throws Exception {
            when(runManager.isRunning()).thenReturn(false);

            mvc.perform(post("/api/v1/test/drain"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NO_ACTIVE_RUN"));

            verify(runManager, never()).drain();
        }

        @Test
        @DisplayName("POST /test/drain returns 202 and calls runManager.drain() — MID-TEST-SCALING Phase B graceful drain")
        void drain_returns_202_and_invokes_drain() throws Exception {
            when(runManager.isRunning()).thenReturn(true);

            mvc.perform(post("/api/v1/test/drain"))
                    .andExpect(status().isAccepted());

            verify(runManager).drain();
        }
    }

    // -----------------------------------------------------------------------
    // Snapshot fixtures
    // -----------------------------------------------------------------------

    private static CurrentRun.Snapshot preparingSnapshot(String runId) {
        return new CurrentRun.Snapshot(
                TestState.PREPARING,
                runId, "us-east-1",
                Instant.parse("2026-05-03T10:00:00Z"), null,
                null, null, null,
                0L, 0L, 0L, null,
                "SKIPPED", null, null);
    }

    private static CurrentRun.Snapshot completedSnapshot(String runId) {
        return new CurrentRun.Snapshot(
                TestState.COMPLETED,
                runId, "us-east-1",
                Instant.parse("2026-05-03T10:00:00Z"),
                Instant.parse("2026-05-03T10:00:42Z"),
                9999L, 0, null,
                100L, 42L, 0L, 0L,
                "SKIPPED", null, null);
    }
}
