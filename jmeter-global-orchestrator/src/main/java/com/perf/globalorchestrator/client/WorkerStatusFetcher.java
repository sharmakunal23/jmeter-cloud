package com.perf.globalorchestrator.client;

import com.perf.globalorchestrator.region.RegionRegistry;
import com.perf.globalorchestrator.region.RegionRouter;
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
 * region ({@code POST /api/v1/workers/status}) for the relay-reachable workers,
 * and one direct call per worker the hub reaches itself — an operator-declared
 * worker's address is not under the region's headless service, so the relay
 * cannot serve it (CLUSTER-CAPACITY: both kinds share a pool). A region the
 * probe last saw unreachable has its relay batch skipped outright — its spun
 * workers simply have no answer this tick — while declared workers are still
 * asked directly, since their reachability does not ride on the regional's.
 */
@Component
public class WorkerStatusFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerStatusFetcher.class);

    private final LocalOrchestratorClient direct;
    private final RegionalClient regional;
    private final RegionRegistry regions;
    private final RegionRouter router;

    public WorkerStatusFetcher(LocalOrchestratorClient direct, RegionalClient regional,
                               RegionRegistry regions, RegionRouter router) {
        this.direct = direct;
        this.regional = regional;
        this.regions = regions;
        this.router = router;
    }

    /** {@code podName → snapshot} for every worker that answered; absent means no answer. */
    public Map<String, Map<String, Object>> fetch(List<WorkerRef> workers) {
        Map<String, List<WorkerRef>> byRegion = new LinkedHashMap<>();
        for (WorkerRef w : workers) {
            byRegion.computeIfAbsent(w.region() == null ? "" : w.region(), k -> new ArrayList<>()).add(w);
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        byRegion.forEach((region, refs) -> {
            List<WorkerRef> relayRefs = new ArrayList<>();
            List<WorkerRef> directRefs = new ArrayList<>();
            for (WorkerRef ref : refs) {
                (router.relayable(ref) ? relayRefs : directRefs).add(ref);
            }
            if (!directRefs.isEmpty()) {
                fetchDirect(directRefs, out);
            }
            if (relayRefs.isEmpty()) {
                return;
            }
            boolean knownDown = regions.statusOf(region).map(RegionStatus::reachable)
                    .map(Boolean.FALSE::equals).orElse(false);
            if (knownDown) {
                LOG.debug("status fetch skipped for {} member(s): region {} is unreachable", relayRefs.size(), region);
                return;
            }
            // Deregistered between relayable() and here — the registry is
            // reloadable now, so this is a live race, not a can't-happen.
            String url = regions.urlOf(region).orElse(null);
            if (url == null) return;
            try {
                regional.statusBatch(url, relayRefs.stream().map(WorkerRef::podName).toList())
                        .forEach((name, snap) -> snap.ifPresent(s -> out.put(name, s)));
            } catch (RuntimeException e) {
                LOG.debug("status batch for region {} failed: {}", region, e.getMessage());
            }
        });
        return out;
    }

    private void fetchDirect(List<WorkerRef> refs, Map<String, Map<String, Object>> out) {
        // One virtual thread per worker: k unreachable workers cost one
        // 5 s timeout, not k of them, and this runs on the UI's poll thread.
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Optional<Map<String, Object>>>> futures = new ArrayList<>();
            for (WorkerRef ref : refs) futures.add(pool.submit(() -> direct.getTestStatus(ref)));
            for (int i = 0; i < refs.size(); i++) {
                WorkerRef ref = refs.get(i);
                try {
                    futures.get(i).get().ifPresent(snap -> out.put(ref.podName(), snap));
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    LOG.debug("status fetch for {} failed: {}", ref.podName(), e.toString());
                }
            }
        }
    }
}
