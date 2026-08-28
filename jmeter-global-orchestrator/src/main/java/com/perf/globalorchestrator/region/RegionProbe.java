package com.perf.globalorchestrator.region;

import com.perf.globalorchestrator.client.RegionalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls every routed region's {@code GET /api/v1/capabilities} and records
 * the answer in {@link RegionRegistry} — the only source of "reachable" and
 * of the per-region image and DNS shape the provisioner needs. A region is
 * marked unreachable only after {@code missesBeforeUnreachable} consecutive
 * misses and reachable again on the first success; each transition is logged
 * once.
 */
@Component
public class RegionProbe {

    private static final Logger LOG = LoggerFactory.getLogger(RegionProbe.class);

    private final RegionRegistry registry;
    private final RegionalClient client;
    private final int missesBeforeUnreachable;
    private final Map<String, Integer> misses = new ConcurrentHashMap<>();

    public RegionProbe(RegionRegistry registry, RegionalClient client) {
        this(registry, client, 3);
    }

    @Autowired
    public RegionProbe(RegionRegistry registry, RegionalClient client,
                       @Value("${globalOrchestrator.regionProbe.missesBeforeUnreachable:3}") int missesBeforeUnreachable) {
        this.registry = registry;
        this.client = client;
        this.missesBeforeUnreachable = Math.max(1, missesBeforeUnreachable);
    }

    @Scheduled(fixedDelayString = "${globalOrchestrator.regionProbe.intervalMs:15000}",
               initialDelayString = "${globalOrchestrator.regionProbe.initialDelayMs:2000}")
    public void probe() {
        for (String region : registry.routedIds()) {
            String url = registry.urlOf(region).orElseThrow();
            Boolean before = registry.statusOf(region).map(RegionStatus::reachable).orElse(null);
            try {
                RegionCapabilities caps = client.capabilities(url);
                misses.remove(region);
                registry.markReachable(region, caps);
                if (!Boolean.TRUE.equals(before)) {
                    LOG.info("region {} REACHABLE at {} (image={}, workers at {podName}.{}:{})",
                            region, url, caps.image(), caps.headlessService(), caps.localOrchestratorPort());
                }
                if (caps.region() != null && !caps.region().equals(region)) {
                    LOG.warn("region {} at {} reports itself as '{}' — check REGIONS and the regional's REGION",
                            region, url, caps.region());
                }
            } catch (RuntimeException e) {
                // Hysteresis: one missed probe on a busy WAN is not an outage.
                int n = misses.merge(region, 1, Integer::sum);
                if (n < missesBeforeUnreachable && !Boolean.FALSE.equals(before)) {
                    LOG.debug("region {} probe miss {}/{}: {}", region, n, missesBeforeUnreachable, e.getMessage());
                    continue;
                }
                registry.markUnreachable(region, e.getMessage());
                if (!Boolean.FALSE.equals(before)) {
                    LOG.warn("region {} UNREACHABLE at {} after {} misses: {}", region, url, n, e.getMessage());
                }
            }
        }
    }
}
