package com.perf.globalorchestrator.region;

import com.perf.globalorchestrator.domain.Region;
import com.perf.globalorchestrator.repo.RegionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The hub's view of every registered cluster ({@code ORCH_REGION},
 * CLUSTER-CAPACITY): identity + URL come from the database — clusters are
 * registered at runtime, not configured at boot — and the last probe's
 * reachability verdict lives in memory per replica ({@link RegionProbe} fills
 * it in; a restart starts from "unknown"). {@link #reload()} refreshes the
 * snapshot: the probe calls it once per tick, and the registration flow calls
 * it after every write, so cross-replica convergence is at most one tick.
 */
@Component
public class RegionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(RegionRegistry.class);

    private final RegionRepository repo;
    /** region id → regionalUrl, in REGION order — the routing snapshot. */
    private volatile Map<String, String> urls = Map.of();
    /** Probe verdicts, kept across reloads for ids that remain registered. */
    private final Map<String, RegionStatus> status = new ConcurrentHashMap<>();

    public RegionRegistry(RegionRepository repo) {
        this.repo = repo;
        try {
            reload();
        } catch (RuntimeException e) {
            // Boot must not depend on the DB being warm — the probe's first
            // tick reloads.
            LOG.warn("RegionRegistry initial load failed (probe will retry): {}", e.toString());
        }
    }

    /**
     * Re-reads {@code ORCH_REGION} and reconciles the status map.
     *
     * <p><b>Synchronized:</b> the probe tick, register, update and delete all
     * call this, and the body is a read-modify-write (build the snapshot,
     * retain the live statuses, publish). Interleaved, a slow reader's older
     * snapshot could overwrite a newer one and hide a just-registered cluster
     * until the next tick.
     */
    public synchronized void reload() {
        Map<String, String> fresh = new LinkedHashMap<>();
        for (Region r : repo.findAll()) {
            fresh.put(r.region(), r.regionalUrl());
            status.compute(r.region(), (k, s) -> {
                if (s == null) return new RegionStatus(k, r.regionalUrl(), true, null, null, null, null);
                // A re-registered URL invalidates the old verdict's address.
                return r.regionalUrl().equals(s.url())
                        ? s
                        : new RegionStatus(k, r.regionalUrl(), true, null, s.lastSeenAt(), null, null);
            });
        }
        status.keySet().retainAll(fresh.keySet());
        this.urls = fresh;
    }

    /** Every registered region id, in REGION order. */
    public List<String> ids() {
        return List.copyOf(urls.keySet());
    }

    /** Every cluster fronts a regional now — same as {@link #ids()}. */
    public List<String> routedIds() {
        return ids();
    }

    public boolean isRouted(String region) {
        return region != null && urls.containsKey(region);
    }

    public Optional<String> urlOf(String region) {
        return region == null ? Optional.empty() : Optional.ofNullable(urls.get(region));
    }

    /** The regional URL, or {@link RegionUnavailableException} for an unregistered region. */
    public String requireUrl(String region) {
        return urlOf(region).orElseThrow(() -> new RegionUnavailableException(region,
                "region '" + region + "' is not a registered cluster — register it under "
                        + "Clusters (POST /api/v1/regions) to provision there"));
    }

    public Optional<RegionCapabilities> capabilitiesOf(String region) {
        RegionStatus s = region == null ? null : status.get(region);
        return s == null ? Optional.empty() : Optional.ofNullable(s.capabilities());
    }

    public Optional<RegionStatus> statusOf(String region) {
        return region == null ? Optional.empty() : Optional.ofNullable(status.get(region));
    }

    /** Every registered region's probe status, in REGION order. */
    public List<RegionStatus> all() {
        return urls.keySet().stream().map(status::get).filter(java.util.Objects::nonNull).toList();
    }

    public void markReachable(String region, RegionCapabilities caps) {
        status.computeIfPresent(region, (k, s) -> s.reachable(caps, Instant.now()));
    }

    public void markUnreachable(String region, String error) {
        status.computeIfPresent(region, (k, s) -> s.unreachable(error));
    }
}
