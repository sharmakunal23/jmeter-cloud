package com.perf.globalorchestrator.client;

import com.perf.globalorchestrator.region.RegionCapabilities;
import com.perf.globalorchestrator.region.RegionProperties;
import com.perf.globalorchestrator.region.RegionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WorkerStatusFetcher — one call per routed region, direct elsewhere, nothing to a region known down")
class WorkerStatusFetcherTest {

    private static final String URL = "http://na-east-control-plane:30088";

    @Test
    void routesAndBatches() {
        RegionRegistry regions = new RegionRegistry(new RegionProperties("na-east=" + URL + ",lab"));
        regions.markReachable("na-east", new RegionCapabilities("na-east", "jmeter-cloud", "workers", "img", 8080, "dev"));
        LocalOrchestratorClient direct = mock(LocalOrchestratorClient.class);
        RegionalClient regional = mock(RegionalClient.class);
        WorkerRef e1 = new WorkerRef("na-east", "e-1", "http://e-1.workers:8080");
        WorkerRef e2 = new WorkerRef("na-east", "e-2", "http://e-2.workers:8080");
        WorkerRef lab = new WorkerRef("lab", "lab-1", "http://lab-1:8080");
        when(regional.statusBatch(URL, List.of("e-1", "e-2"))).thenReturn(Map.of(
                "e-1", Optional.of(Map.of("state", "RUNNING")),
                "e-2", Optional.empty()));
        when(direct.getTestStatus(lab)).thenReturn(Optional.of(Map.of("state", "COMPLETED")));

        Map<String, Map<String, Object>> out = new WorkerStatusFetcher(direct, regional, regions).fetch(List.of(e1, e2, lab));

        assertThat(out).containsOnlyKeys("e-1", "lab-1");
        assertThat(out.get("e-1")).containsEntry("state", "RUNNING");
        verify(direct, never()).getTestStatus(e1);
    }

    @Test
    void skipsARegionKnownToBeDown() {
        RegionRegistry regions = new RegionRegistry(new RegionProperties("na-east=" + URL));
        regions.markUnreachable("na-east", "refused");
        RegionalClient regional = mock(RegionalClient.class);
        WorkerStatusFetcher fetcher = new WorkerStatusFetcher(mock(LocalOrchestratorClient.class), regional, regions);

        assertThat(fetcher.fetch(List.of(new WorkerRef("na-east", "e-1", "http://e-1.workers:8080")))).isEmpty();
        verify(regional, never()).statusBatch(anyString(), anyList());
    }

    @Test
    void aFailedBatchIsNoAnswersNotAnError() {
        RegionRegistry regions = new RegionRegistry(new RegionProperties("na-east=" + URL));
        RegionalClient regional = mock(RegionalClient.class);
        when(regional.statusBatch(anyString(), any())).thenThrow(new RuntimeException("boom"));
        WorkerStatusFetcher fetcher = new WorkerStatusFetcher(mock(LocalOrchestratorClient.class), regional, regions);

        assertThat(fetcher.fetch(List.of(new WorkerRef("na-east", "e-1", "http://e-1.workers:8080")))).isEmpty();
    }
}
