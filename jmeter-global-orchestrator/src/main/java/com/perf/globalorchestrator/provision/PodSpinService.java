package com.perf.globalorchestrator.provision;

import com.perf.globalorchestrator.repo.PodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
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

    private final long readyTimeoutMs;
    private final long capacityWaitMs;

    public PodSpinService(PodRepository pods, PodNameAllocator allocator, PodProvisioner provisioner, long readyTimeoutMs) {
        this(pods, allocator, provisioner, readyTimeoutMs, readyTimeoutMs);
    }

    @Autowired
    public PodSpinService(PodRepository pods, PodNameAllocator allocator, PodProvisioner provisioner,
                          @Value("${globalOrchestrator.podProvisioner.spinReadyTimeoutMs:90000}") long readyTimeoutMs,
                          @Value("${globalOrchestrator.podProvisioner.spinCapacityWaitMs:20000}") long capacityWaitMs) {
        this.pods = pods;
        this.allocator = allocator;
        this.provisioner = provisioner;
        this.readyTimeoutMs = readyTimeoutMs;
        this.capacityWaitMs = capacityWaitMs;
    }

    /**
     * Allocates a name, registers a placeholder row, starts the container,
     * and records the digest+provisionedAt. Throws on any failure; rolls
     * back the placeholder row first so the allocator's accounting stays
     * consistent.
     *
     * @return the {@link SpinResult} with the assigned name + provisioned metadata
     */
    /**
     * The Capacity tab's synchronous spin: waits at most {@code capacityWaitMs}
     * (under the proxy's timeout) and answers {@code ready=false} if the pod
     * is still starting — the liveness probe admits it later.
     */
    public SpinResult spin(String applicationId, String applicationName, String region) {
        return start(applicationId, applicationName, region, reserve(applicationId, applicationName, region), capacityWaitMs);
    }

    /**
     * Allocates the next free name and writes its placeholder row — LOST,
     * unclaimable — before anything is created, so the allocator's view is
     * the registry and a concurrent reservation loses on the primary key and
     * simply allocates again. Cheap; call it serially, then {@link #start} in
     * parallel.
     */
    public String reserve(String applicationId, String applicationName, String region) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String podName = allocator.allocate(applicationId, applicationName, region);
            try {
                pods.registerStarting(podName, region, provisioner.baseUrlFor(region, podName), applicationId);
                return podName;
            } catch (DuplicateKeyException race) {
                LOG.debug("reserve: {} taken concurrently, allocating again", podName);
            }
        }
        throw new IllegalStateException("could not reserve a worker name for " + applicationName + " in " + region);
    }

    /**
     * Creates the reserved pod through its region and waits (bounded) for the
     * kubelet to report it ready, which is when the row flips LOST → IDLE.
     * Rolls the placeholder back if the region refuses to create it.
     */
    public SpinResult start(String applicationId, String applicationName, String region, String podName) {
        return start(applicationId, applicationName, region, podName, readyTimeoutMs);
    }

    public SpinResult start(String applicationId, String applicationName, String region, String podName, long waitMs) {
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
        boolean ready = awaitReady(region, podName, waitMs);
        if (ready) {
            pods.heartbeat(podName); // LOST → IDLE: claimable now
        } else {
            LOG.info("spin {}: not ready after {} ms — left LOST; WorkerLivenessProbe admits it when the kubelet does",
                    podName, waitMs);
        }
        return new SpinResult(podName, result.baseUrl(),
                result.imageDigest(), result.createdAt(), ready);
    }

    private boolean awaitReady(String region, String podName, long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (provisioner.isReady(region, podName)) return true;
            } catch (RuntimeException e) {
                LOG.debug("isReady({}) failed: {}", podName, e.toString());
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** {@code ready=false} means the pod exists but is still LOST (starting); it becomes IDLE when the kubelet reports it ready. */
    public record SpinResult(
            String podName,
            String baseUrl,
            String imageDigest,
            java.time.Instant createdAt,
            boolean ready) {}
}
