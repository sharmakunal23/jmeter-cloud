package com.perf.globalorchestrator.region;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory view of every configured region and, for the routed ones, what
 * the last probe saw. Configuration is {@link RegionProperties}; nothing here
 * is persisted, so a restart starts from "unknown" and the first probe tick
 * fills it in.
 */
@Component
public class RegionRegistry {

    private final RegionProperties properties;
    private final Map<String, RegionStatus> status = new ConcurrentHashMap<>();

    public RegionRegistry(RegionProperties properties) {
        this.properties = properties;
        for (String id : properties.ids()) {
            String url = properties.urlOf(id).orElse(null);
            status.put(id, new RegionStatus(id, url, url != null, null, null, null, null));
        }
    }

    public List<String> ids() {
        return properties.ids();
    }

    public List<String> routedIds() {
        return List.copyOf(properties.routed().keySet());
    }

    public boolean isRouted(String region) {
        return region != null && properties.urlOf(region).isPresent();
    }

    public Optional<String> urlOf(String region) {
        return region == null ? Optional.empty() : properties.urlOf(region);
    }

    /** The regional URL, or {@link RegionUnavailableException} when the region is direct or unknown. */
    public String requireUrl(String region) {
        return urlOf(region).orElseThrow(() -> new RegionUnavailableException(region,
                "region '" + region + "' has no regional orchestrator — add it to REGIONS as "
                        + region + "=http://… to provision there"));
    }

    public Optional<RegionCapabilities> capabilitiesOf(String region) {
        RegionStatus s = region == null ? null : status.get(region);
        return s == null ? Optional.empty() : Optional.ofNullable(s.capabilities());
    }

    public Optional<RegionStatus> statusOf(String region) {
        return region == null ? Optional.empty() : Optional.ofNullable(status.get(region));
    }

    /** Every region in declaration order. */
    public List<RegionStatus> all() {
        return properties.ids().stream().map(status::get).toList();
    }

    public void markReachable(String region, RegionCapabilities caps) {
        status.computeIfPresent(region, (k, s) -> s.reachable(caps, Instant.now()));
    }

    public void markUnreachable(String region, String error) {
        status.computeIfPresent(region, (k, s) -> s.unreachable(error));
    }
}
