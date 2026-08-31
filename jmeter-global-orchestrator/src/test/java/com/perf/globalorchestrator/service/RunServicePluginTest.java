package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.client.LocalOrchestratorClient;
import com.perf.globalorchestrator.client.WorkerStatusFetcher;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.PluginRef;
import com.perf.globalorchestrator.http.StartRunRequest;
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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UX-DYNAMICS T3 — the launch gate for the plugin library: an unknown
 * pluginId rejects the launch before anything is claimed, and the fan-out
 * wire refs carry exactly {blobId, fileName}.
 */
@DisplayName("RunService — plugin resolution at launch")
class RunServicePluginTest {

    private static final String UNKNOWN_ID = "01HXC2VQK4M9N6P5T0YBX2WZ99";

    private PluginRepository plugins;
    private RunService service;

    @BeforeEach
    void setUp() throws Exception {
        plugins = mock(PluginRepository.class);
        service = new RunService(
                mock(RunRepository.class),
                mock(RunEventRepository.class),
                mock(RunAuditWriter.class),
                mock(PodRepository.class),
                mock(ApplicationRepository.class),
                mock(GroupCapacityRepository.class),
                mock(LocalOrchestratorClient.class),
                mock(WorkerStatusFetcher.class),
                mock(RunMetricsRepository.class),
                mock(MetricsGroupResolver.class),
                mock(RunTrendRepository.class),
                plugins,
                null,
                "us-east-1", 1, 100, 1000L,
                // Terminal-run announcements are the workflow engine's wake-up;
                // WorkflowRunCompletionListenerTest covers what they do.
                event -> { });
        // The @Transactional self-proxy is Spring-wired; point it at the plain
        // instance so startRun's self.openRunAndClaimPods call runs inline.
        Field self = RunService.class.getDeclaredField("self");
        self.setAccessible(true);
        self.set(service, service);
    }

    @Test
    @DisplayName("an unknown pluginId rejects the launch with 400 before any pod is touched")
    void unknownPluginId_rejects() {
        when(plugins.findById(UNKNOWN_ID)).thenReturn(Optional.empty());
        StartRunRequest request = new StartRunRequest(
                "plan-blob", null, null, 1, List.of(), List.of(),
                null, null, null, List.of(UNKNOWN_ID), null);
        assertThatThrownBy(() -> service.startRun(request, false, Actor.ANONYMOUS_ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown pluginId")
                .hasMessageContaining(UNKNOWN_ID);
    }

    @Test
    @DisplayName("the fan-out wire refs are {blobId, fileName} only — name/version stay hub-side")
    void pluginWireRefs_shape() {
        List<Map<String, String>> refs = RunService.pluginWireRefs(List.of(
                new PluginRef("p1", "jpgc-casutg", "3.1", "blob-1", "casutg.jar"),
                new PluginRef("p2", "bzm-parallel", "0.13", "blob-2", "parallel.zip")));
        assertThat(refs).containsExactly(
                Map.of("blobId", "blob-1", "fileName", "casutg.jar"),
                Map.of("blobId", "blob-2", "fileName", "parallel.zip"));
    }
}
