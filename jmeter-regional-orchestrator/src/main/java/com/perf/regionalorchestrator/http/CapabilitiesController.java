package com.perf.regionalorchestrator.http;

import com.perf.regionalorchestrator.provision.ProvisionerProperties;
import com.perf.regionalorchestrator.provision.RegionalProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this region is: its id, namespace, worker image and version. The global
 * orchestrator's region probe polls it, so a {@code 200} here is what marks the
 * region REACHABLE.
 */
@RestController
@RequestMapping("/api/v1")
public class CapabilitiesController {

    public record Capabilities(String region, String namespace, String headlessService,
                               String image, int localOrchestratorPort, String version) {}

    private final RegionalProperties region;
    private final ProvisionerProperties props;
    private final String version;

    public CapabilitiesController(RegionalProperties region, ProvisionerProperties props,
                                  @Value("${regionalOrchestrator.version:dev}") String version) {
        this.region = region;
        this.props = props;
        this.version = version;
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return new Capabilities(region.region(), props.namespace(), props.headlessService(),
                props.image(), props.localOrchestratorPort(), version);
    }
}
