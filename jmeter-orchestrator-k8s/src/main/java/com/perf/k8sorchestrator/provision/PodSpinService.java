package com.perf.k8sorchestrator.provision;

import com.perf.k8sorchestrator.repo.PodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Shared spin-a-new-pod sequence used by both {@code CapacityController.spin}
 * (operator-driven scale-up) and {@code PodRecycler} (WORKER-HYGIENE Phase D
 * drain-and-replace). Encapsulates the "allocate name → register placeholder
 * row → createAndStart container → back-fill digest+provisionedAt" handshake
 * with the same rollback-on-failure semantics either caller relies on.
 *
 * <p>Capacity-ceiling enforcement is NOT part of this service — that lives
 * in {@code CapacityController.spin} (operator-asked spin can exceed the
 * ceiling; recycle replacement is a 1-for-1 swap that's already accounted
 * for in the ceiling).
 */
@Service
public class PodSpinService {

    private static final Logger LOG = LoggerFactory.getLogger(PodSpinService.class);

    private final PodRepository pods;
    private final PodNameAllocator allocator;
    private final PodProvisioner provisioner;

    public PodSpinService(PodRepository pods, PodNameAllocator allocator, PodProvisioner provisioner) {
        this.pods = pods;
        this.allocator = allocator;
        this.provisioner = provisioner;
    }

    /**
     * Allocates a name, registers a placeholder row, starts the container,
     * and records the digest+provisionedAt. Throws on any failure; rolls
     * back the placeholder row first so the allocator's accounting stays
     * consistent.
     *
     * @return the {@link SpinResult} with the assigned name + provisioned metadata
     */
    public SpinResult spin(String applicationId, String applicationName, String region) {
        String podName = allocator.allocate(applicationId, applicationName, region);
        // Placeholder row BEFORE container start — same reasons as
        // CapacityController.spin: allocator's "what's taken" view is the
        // registry, and concurrent cap-checks need this row to count.
        String predictedBaseUrl = "http://" + podName + ":8080";
        pods.register(podName, region, predictedBaseUrl, applicationId);
        PodSpec spec = new PodSpec(podName, applicationId, applicationName, region);
        ProvisionResult result;
        try {
            result = provisioner.createAndStart(spec);
        } catch (RuntimeException e) {
            try {
                pods.deleteByPodId(podName);
            } catch (RuntimeException cleanup) {
                LOG.warn("Rollback of placeholder row {} failed: {}", podName, cleanup.toString());
            }
            throw e;
        }
        pods.recordProvisionMetadata(podName, result.imageDigest(), result.createdAt());
        return new SpinResult(podName, result.baseUrl(),
                result.imageDigest(), result.createdAt());
    }

    public record SpinResult(
            String podName,
            String baseUrl,
            String imageDigest,
            java.time.Instant createdAt) {}
}
