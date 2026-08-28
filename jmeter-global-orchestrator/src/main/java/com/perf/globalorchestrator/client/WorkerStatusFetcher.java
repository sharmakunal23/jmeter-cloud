package com.perf.globalorchestrator.client;

import com.perf.globalorchestrator.region.RegionRegistry;
import com.perf.globalorchestrator.region.RegionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches many workers' {@code GET /api/v1/test} snapshots with one call per
 * routed region ({@code POST /api/v1/workers/status}) and one direct call per
 * worker in a direct region. A region the probe last saw unreachable is
 * skipped outright — its workers simply have no answer this tick — so a dead
 * data center costs nothing instead of one timeout per member.
 */
@Component
public class WorkerStatusFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerStatusFetcher.class);

    private final LocalOrchestratorClient direct;
    private final RegionalClient regional;
    private final RegionRegistry regions;

    public WorkerStatusFetcher(LocalOrchestratorClient direct, RegionalClient regional, RegionRegistry regions) {
        this.direct = direct;
        this.regional = regional;
        this.regions = regions;
    }

    /** {@code podName → snapshot} for every worker that answered; absent means no answer. */
    public Map<String, Map<String, Object>> fetch(List<WorkerRef> workers) {
        Map<String, List<WorkerRef>> byRegion = new LinkedHashMap<>();
        for (WorkerRef w : workers) {
            byRegion.computeIfAbsent(w.region() == null ? "" : w.region(), k -> new ArrayList<>()).add(w);
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        byRegion.forEach((region, refs) -> {
            Optional<String> url = regions.urlOf(region);
            if (url.isEmpty()) {
                for (WorkerRef ref : refs) {
                    direct.getTestStatus(ref).ifPresent(snap -> out.put(ref.podName(), snap));
                }
                return;
            }
            boolean knownDown = regions.statusOf(region).map(RegionStatus::reachable)
                    .map(Boolean.FALSE::equals).orElse(false);
            if (knownDown) {
                LOG.debug("status fetch skipped for {} member(s): region {} is unreachable", refs.size(), region);
                return;
            }
            try {
                regional.statusBatch(url.get(), refs.stream().map(WorkerRef::podName).toList())
                        .forEach((name, snap) -> snap.ifPresent(s -> out.put(name, s)));
            } catch (RuntimeException e) {
                LOG.debug("status batch for region {} failed: {}", region, e.getMessage());
            }
        });
        return out;
    }
}
