package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.client.LocalOrchestratorClient;
import com.perf.globalorchestrator.client.WorkerStatusFetcher;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.MemberState;
import com.perf.globalorchestrator.domain.PluginRef;
import com.perf.globalorchestrator.domain.RunEventType;
import com.perf.globalorchestrator.domain.RunFleetMember;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.PluginRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.RunEventRepository;
import com.perf.globalorchestrator.repo.RunMetricsRepository;
import com.perf.globalorchestrator.repo.RunRepository;
import com.perf.globalorchestrator.repo.RunTrendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * UX-DYNAMICS events — the fan-out provenance recorders: the worker 202's
 * {@code dataFilesReused} parse, the reused/downloaded split event, and the
 * launch-only TEST_PLAN_UPLOADED / PLUGINS_UPLOADED rule (joiner fan-outs are
 * never re-announced).
 */
@DisplayName("RunService — fan-out provenance events")
class RunServiceProvenanceEventsTest {

    private static final String RUN_ID = "01HXC2VQK4M9N6P5T0YBX2WZ02";

    private RunAuditWriter audit;
    private RunService service;

    @BeforeEach
    void setUp() {
        audit = mock(RunAuditWriter.class);
        service = new RunService(
                mock(RunRepository.class),
                mock(RunEventRepository.class),
                audit,
                mock(PodRepository.class),
                mock(ApplicationRepository.class),
                mock(GroupCapacityRepository.class),
                mock(LocalOrchestratorClient.class),
                mock(WorkerStatusFetcher.class),
                mock(RunMetricsRepository.class),
                mock(MetricsGroupResolver.class),
                mock(RunTrendRepository.class),
                mock(PluginRepository.class),
                null,
                "us-east-1", 1, 100, 1000L,
                // Terminal-run announcements are the workflow engine's wake-up;
                // WorkflowRunCompletionListenerTest covers what they do.
                event -> { });
    }

    private static RunFleetMember member(String workerId, Long joinedAtSecond) {
        return new RunFleetMember(RUN_ID, workerId, "na-east", MemberState.ACCEPTED, null, 202,
                "http://" + workerId + ":8080", Instant.now(), Instant.now(), null,
                Map.of(), joinedAtSecond, null);
    }

    @Test
    @DisplayName("parseDataFilesReused reads the 202 body's flag; absent or unparsable = null")
    void parseFlag() {
        assertThat(RunService.parseDataFilesReused("{\"runId\":\"r\",\"dataFilesReused\":true}")).isTrue();
        assertThat(RunService.parseDataFilesReused("{\"dataFilesReused\":false}")).isFalse();
        assertThat(RunService.parseDataFilesReused("{\"runId\":\"r\"}")).isNull();
        assertThat(RunService.parseDataFilesReused("not json")).isNull();
        assertThat(RunService.parseDataFilesReused(null)).isNull();
    }

    @Test
    @DisplayName("a mixed reuse split emits DATA_FILES_REUSED with both worker lists")
    void mixedSplit_reusedEvent() {
        service.recordDataFilesProvenance(RUN_ID, "data-blob", false, Map.of(
                "w1", new RunService.FanoutOutcome(MemberState.ACCEPTED, 202, null, Boolean.TRUE),
                "w2", new RunService.FanoutOutcome(MemberState.ACCEPTED, 202, null, Boolean.FALSE)));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(audit).record(eq(RUN_ID), eq(RunEventType.DATA_FILES_REUSED), any(Actor.class),
                payload.capture(), eq("ok"));
        RunService.DataFilesProvenancePayload p = (RunService.DataFilesProvenancePayload) payload.getValue();
        assertThat(p.reused()).containsExactly("w1");
        assertThat(p.downloaded()).containsExactly("w2");
        assertThat(p.refreshRequested()).isFalse();
    }

    @Test
    @DisplayName("all-downloaded emits DATA_FILES_UPLOADED; no provenance or no blob emits nothing")
    void uploadedAndSkips() {
        service.recordDataFilesProvenance(RUN_ID, "data-blob", true, Map.of(
                "w1", new RunService.FanoutOutcome(MemberState.ACCEPTED, 202, null, Boolean.FALSE)));
        verify(audit).record(eq(RUN_ID), eq(RunEventType.DATA_FILES_UPLOADED), any(Actor.class),
                any(), eq("ok"));

        // Legacy workers omit the flag (null) — tolerant: no event.
        RunAuditWriter quiet = mock(RunAuditWriter.class);
        service.recordDataFilesProvenance(RUN_ID, null, false, Map.of(
                "w1", new RunService.FanoutOutcome(MemberState.ACCEPTED, 202, null, Boolean.TRUE)));
        service.recordDataFilesProvenance(RUN_ID, "data-blob", false, Map.of(
                "w1", new RunService.FanoutOutcome(MemberState.ACCEPTED, 202, null)));
        verifyNoInteractions(quiet);
    }

    @Test
    @DisplayName("original launch announces plan + plugins to ACCEPTED workers only")
    void originalLaunch_announces() {
        List<RunFleetMember> members = List.of(member("w1", null), member("w2", null));
        service.recordLaunchArtifacts(RUN_ID, members, "plan-blob",
                List.of(new PluginRef("p1", "demo-noop", "1.0.0", "blob-1", "demoNoopPlugin.jar")),
                Map.of(
                        "w1", new RunService.FanoutOutcome(MemberState.ACCEPTED, 202, null),
                        "w2", new RunService.FanoutOutcome(MemberState.FAILED, 0, "boom")));
        ArgumentCaptor<Object> plan = ArgumentCaptor.forClass(Object.class);
        verify(audit).record(eq(RUN_ID), eq(RunEventType.TEST_PLAN_UPLOADED), any(Actor.class),
                plan.capture(), eq("ok"));
        assertThat(((RunService.TestPlanUploadedPayload) plan.getValue()).workers()).containsExactly("w1");
        ArgumentCaptor<Object> plug = ArgumentCaptor.forClass(Object.class);
        verify(audit).record(eq(RUN_ID), eq(RunEventType.PLUGINS_UPLOADED), any(Actor.class),
                plug.capture(), eq("ok"));
        assertThat(((RunService.PluginsUploadedPayload) plug.getValue()).plugins())
                .containsExactly("demo-noop@1.0.0");
    }

    @Test
    @DisplayName("a joiner-only fan-out (scale-up) announces nothing")
    void joinerFanout_silent() {
        service.recordLaunchArtifacts(RUN_ID, List.of(member("w3", 42L)), "plan-blob", List.of(),
                Map.of("w3", new RunService.FanoutOutcome(MemberState.ACCEPTED, 202, null)));
        verify(audit, never()).record(anyString(), any(RunEventType.class), any(Actor.class),
                any(), anyString());
    }
}
