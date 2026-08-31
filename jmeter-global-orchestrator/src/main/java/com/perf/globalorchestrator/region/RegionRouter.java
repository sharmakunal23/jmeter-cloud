package com.perf.globalorchestrator.region;

import com.perf.globalorchestrator.client.WorkerRef;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Decides the URL to dial for a worker. A cluster-private address
 * ({@code http://{podName}.{headlessService}:8080} — what the region's
 * provisioner hands out) is reached through the region's relay,
 * {@code {regionalUrl}/api/v1/workers/{podName}}; any other address — an
 * operator-declared worker the hub can reach itself — is dialled directly,
 * routed region or not. The relay serves the worker's paths verbatim, so
 * callers append {@code /api/v1/test} etc. either way.
 */
@Component
public class RegionRouter {

    private final RegionRegistry registry;

    public RegionRouter(RegionRegistry registry) {
        this.registry = registry;
    }

    public String dial(WorkerRef ref) {
        return registry.urlOf(ref.region())
                .filter(url -> isClusterPrivate(ref.baseUrl(), headlessServiceOf(ref.region())))
                .map(url -> url + "/api/v1/workers/" + ref.podName())
                .orElse(ref.baseUrl());
    }

    /**
     * Whether {@link #dial} would go through the region's relay — false for an
     * operator-declared worker with a hub-reachable address, which batch
     * callers (the status fetcher) must reach directly instead.
     */
    public boolean relayable(WorkerRef ref) {
        return registry.urlOf(ref.region()).isPresent()
                && isClusterPrivate(ref.baseUrl(), headlessServiceOf(ref.region()));
    }

    private String headlessServiceOf(String region) {
        return registry.capabilitiesOf(region).map(RegionCapabilities::headlessService).orElse("workers");
    }

    /** True for a host of the form {@code {podName}.{headlessService}[.namespace.svc…]}. */
    static boolean isClusterPrivate(String baseUrl, String headlessService) {
        if (baseUrl == null) return true; // nothing else to dial
        try {
            String host = URI.create(baseUrl).getHost();
            return host != null && (host.endsWith("." + headlessService) || host.contains("." + headlessService + "."));
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
