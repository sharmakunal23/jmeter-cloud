package com.perf.regionalorchestrator.http;

import com.perf.regionalorchestrator.provision.NamespaceCapacity;
import com.perf.regionalorchestrator.provision.PodProvisioner;
import com.perf.regionalorchestrator.provision.ProvisionerProperties;
import com.perf.regionalorchestrator.provision.ProvisioningCheck;
import com.perf.regionalorchestrator.provision.ProvisioningCheckService;
import com.perf.regionalorchestrator.provision.RegionalProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What this region is: its id, namespace, worker image, footprint and version.
 * The global orchestrator's region probe polls {@code /capabilities}, so a
 * {@code 200} here is what marks the region REACHABLE;
 * {@code /provisioningCheck} is the registration dry-run — can this regional
 * actually create worker Pods (CLUSTER-CAPACITY).
 */
@RestController
@RequestMapping("/api/v1")
public class CapabilitiesController {

    private final PodProvisioner provisioner;
    private final ProvisioningCheckService provisioningChecks;

    public record Capabilities(String region, String namespace, String headlessService,
                               String image, int localOrchestratorPort, String version,
                               /** Live namespace-quota headroom (Track 8); null dimensions are unbounded. */
                               NamespaceCapacity capacity,
                               /** The worker Pod's memory request == limit, MiB. */
                               long workerMemoryMb,
                               /** The worker Pod's ephemeral-storage request == limit (e.g. "5Gi"); null = LimitRange default. */
                               String workerEphemeralStorage) {}

    public record ProvisioningCheckResponse(String region, String image, boolean ok,
                                            List<ProvisioningCheck> checks) {}

    private final RegionalProperties region;
    private final ProvisionerProperties props;
    private final String version;

    public CapabilitiesController(RegionalProperties region, ProvisionerProperties props,
                                  @Value("${regionalOrchestrator.version:dev}") String version,
                                  PodProvisioner provisioner, ProvisioningCheckService provisioningChecks) {
        this.provisioner = provisioner;
        this.provisioningChecks = provisioningChecks;
        this.region = region;
        this.props = props;
        this.version = version;
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        NamespaceCapacity capacity;
        try {
            capacity = provisioner.capacity();
        } catch (RuntimeException e) {
            capacity = NamespaceCapacity.UNBOUNDED;   // a failed quota read must not hide the region
        }
        return new Capabilities(region.region(), props.namespace(), props.headlessService(),
                props.image(), props.localOrchestratorPort(), version, capacity,
                props.workerMemoryMb(), props.shape().ephemeralStorage());
    }

    @GetMapping("/provisioningCheck")
    public ProvisioningCheckResponse provisioningCheck() {
        List<ProvisioningCheck> checks = provisioningChecks.run();
        boolean ok = checks.stream().allMatch(ProvisioningCheck::ok);
        return new ProvisioningCheckResponse(region.region(), props.image(), ok, checks);
    }
}
