package com.perf.globalorchestrator.provision;

import com.perf.globalorchestrator.client.RegionalClient;
import com.perf.globalorchestrator.region.RegionCapabilities;
import com.perf.globalorchestrator.region.RegionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The {@link PodProvisioner} under {@code PROVISIONING_MODE=DYNAMIC}: every
 * call is forwarded to the region's {@code jmeter-regional-orchestrator}, so
 * this service never holds a cluster credential. A region without a regional
 * URL cannot provision — {@link com.perf.globalorchestrator.region.RegionUnavailableException}.
 *
 * <p>{@link #currentImageDigest(String)} and {@link #baseUrlFor(String, String)}
 * come from the region's last probed capabilities, not a live call; before the
 * first probe the digest is {@code null} (the recycler skips the image check)
 * and the URL falls back to the platform default {@code {podName}.workers:8080}.
 */
@Component
@ConditionalOnProvisioningMode(ProvisioningMode.DYNAMIC)
public class RegionalPodProvisioner implements PodProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(RegionalPodProvisioner.class);

    private final RegionRegistry regions;
    private final RegionalClient client;

    public RegionalPodProvisioner(RegionRegistry regions, RegionalClient client) {
        this.regions = regions;
        this.client = client;
    }

    @Override
    public ProvisionResult createAndStart(PodSpec spec) {
        return client.createPod(regions.requireUrl(spec.region()), spec);
    }

    @Override
    public void stopAndRemove(String region, String podName) {
        client.deletePod(regions.requireUrl(region), podName);
    }

    @Override
    public void stop(String region, String podName) {
        client.stopPod(regions.requireUrl(region), podName);
    }

    @Override
    public void start(String region, String podName) {
        client.startPod(regions.requireUrl(region), podName);
    }

    @Override
    public void restart(String region, String podName) {
        client.restartPod(regions.requireUrl(region), podName);
    }

    @Override
    public boolean exists(String region, String podName) {
        return client.getPod(regions.requireUrl(region), podName).exists();
    }

    @Override
    public boolean isRunning(String region, String podName) {
        return client.getPod(regions.requireUrl(region), podName).running();
    }

    @Override
    public boolean isReady(String region, String podName) {
        return client.getPod(regions.requireUrl(region), podName).ready();
    }

    @Override
    public List<ProvisionedPod> listFor(String groupId, String region) {
        if (region != null) {
            return client.listPods(regions.requireUrl(region), groupId, region);
        }
        List<ProvisionedPod> all = new ArrayList<>();
        for (String id : regions.routedIds()) {
            try {
                all.addAll(client.listPods(regions.requireUrl(id), groupId, id));
            } catch (RuntimeException e) {
                LOG.warn("listFor(group={}, region={}) skipped: {}", groupId, id, e.getMessage());
            }
        }
        return all;
    }

    @Override
    public List<ProvisionedPod> listAll(String region) {
        Optional<String> url = regions.urlOf(region);
        if (url.isEmpty()) return List.of();
        return client.listWorkers(url.get()).stream()
                .map(w -> new ProvisionedPod(w.podName(), w.groupId(), region,
                        w.dead() ? "exited" : (w.ready() ? "running" : "created"), java.time.Instant.now(), null))
                .toList();
    }

    @Override
    public Integer availableWorkers(String region) {
        return regions.capabilitiesOf(region).map(RegionCapabilities::workersFree).orElse(null);
    }

    @Override
    public String currentImageDigest(String region) {
        return regions.capabilitiesOf(region).map(RegionCapabilities::image).orElse(null);
    }

    @Override
    public String baseUrlFor(String region, String podName) {
        Optional<RegionCapabilities> caps = regions.capabilitiesOf(region);
        String service = caps.map(RegionCapabilities::headlessService).orElse("workers");
        int port = caps.map(RegionCapabilities::localOrchestratorPort).orElse(8080);
        return "http://" + podName + "." + service + ":" + port;
    }
}
