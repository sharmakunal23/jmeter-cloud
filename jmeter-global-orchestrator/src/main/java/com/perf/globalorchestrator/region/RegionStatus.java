package com.perf.globalorchestrator.region;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * What the hub knows about one region. {@code reachable} is {@code null}
 * until the first probe answers, and always {@code null} for a direct region
 * (there is nothing to probe).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegionStatus(
        String region,
        String url,
        boolean routed,
        Boolean reachable,
        Instant lastSeenAt,
        String lastError,
        RegionCapabilities capabilities) {

    RegionStatus reachable(RegionCapabilities caps, Instant now) {
        return new RegionStatus(region, url, routed, true, now, null, caps);
    }

    RegionStatus unreachable(String error) {
        return new RegionStatus(region, url, routed, false, lastSeenAt, error, capabilities);
    }
}
