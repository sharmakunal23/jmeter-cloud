package com.perf.globalorchestrator.region;

import com.perf.globalorchestrator.client.RegionalClient;
import com.perf.globalorchestrator.provision.PodSpec;
import com.perf.globalorchestrator.repo.RegionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * The on-demand deep validation (CLUSTER-CAPACITY): spins ONE real probe
 * worker in the cluster, waits for the kubelet to report it ready, deletes it,
 * and records PASS/FAIL on the {@code ORCH_REGION} row. Asynchronous by design
 * — a synchronous launch must never provision (a pod boot can outlive the
 * proxy timeout) — so the caller gets {@code 202} and the UI reads the verdict
 * from {@code GET /api/v1/regions/status} on its next poll.
 *
 * <p>The probe pod is named {@code probe-{region}-{epoch}} with groupId
 * {@link #PROBE_GROUP_ID} and never touches {@code ORCH_POD}, so the claim
 * path, the liveness probe and the sweepers cannot see it; the reconciler
 * skips that group by name, so a sweep inside the probe's short window
 * neither adopts it nor logs an FK failure.
 */
@Service
public class TestProvisionService {

    private static final Logger LOG = LoggerFactory.getLogger(TestProvisionService.class);
    private static final int DETAIL_MAX_CHARS = 3900;   // under LAST_PROBE_DETAIL VARCHAR2(4000 CHAR)

    /**
     * The probe pod's group. Deliberately contains a hyphen, which a real
     * {@code applicationGroup.groupId} ({@code [a-z][a-z0-9_]{0,29}}) can never
     * hold — so a probe pod can never be adopted into a live pool and claimed
     * by a run seconds before this service deletes it. It is still a valid
     * Kubernetes label value, which is what the regional stamps it as.
     */
    public static final String PROBE_GROUP_ID = "probe-validation";

    private final RegionalClient client;
    private final RegionRepository repo;
    private final long timeoutMs;

    public TestProvisionService(RegionalClient client, RegionRepository repo,
                                @Value("${globalOrchestrator.regionProbe.testProvisionTimeoutMs:180000}") long timeoutMs) {
        this.client = client;
        this.repo = repo;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Claims the probe slot in the database and runs it on a virtual thread.
     * The URL comes from the caller's {@code ORCH_REGION} row, not the
     * in-memory snapshot — the row is the authority, and a replica whose
     * snapshot has not caught up would otherwise refuse a cluster that is
     * demonstrably registered.
     *
     * @return false when a probe for this cluster is already running (on any replica)
     */
    public boolean start(String region, String regionalUrl) {
        // A probe that outlives its own timeout by a minute is dead, not running.
        if (!repo.tryStartProbe(region, (timeoutMs / 1000) + 60)) {
            return false;
        }
        Thread.ofVirtual().name("testProvision-" + region).start(() -> run(region, regionalUrl));
        return true;
    }

    private void run(String region, String url) {
        String podName = "probe-" + region + "-" + Instant.now().getEpochSecond();
        Instant startedAt = Instant.now();
        try {
            client.createPod(url, new PodSpec(podName, PROBE_GROUP_ID, region));
            String verdict = awaitReady(url, podName);
            long tookS = Duration.between(startedAt, Instant.now()).toSeconds();
            if (verdict == null) {
                record(region, true, "probe worker " + podName + " became ready in " + tookS + " s and was deleted");
            } else {
                record(region, false, "probe worker " + podName + " did not become ready within "
                        + (timeoutMs / 1000) + " s — " + verdict);
            }
        } catch (RuntimeException e) {
            record(region, false, "probe worker " + podName + " could not be created — "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            try {
                client.deletePod(url, podName);
            } catch (RuntimeException e) {
                LOG.warn("testProvision {}: probe pod {} delete failed: {}", region, podName, e.toString());
            }
        }
    }

    /** @return null on ready; otherwise the last observed state, for the FAIL detail */
    private String awaitReady(String url, String podName) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = "no state observed";
        while (System.currentTimeMillis() < deadline) {
            try {
                RegionalClient.PodState s = client.getPod(url, podName);
                if (s.ready()) return null;
                if (s.dead()) return "pod is dead: " + (s.reason() == null ? "no reason reported" : s.reason());
                last = "pod exists=" + s.exists() + " running=" + s.running()
                        + (s.reason() == null ? "" : " reason=" + s.reason());
            } catch (RuntimeException e) {
                last = "state poll failed: " + e.getMessage();
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        }
        return last;
    }

    private void record(String region, boolean pass, String detail) {
        String bounded = detail.length() > DETAIL_MAX_CHARS ? detail.substring(0, DETAIL_MAX_CHARS) : detail;
        repo.recordProbe(region, pass, bounded);
        LOG.info("testProvision {}: {} — {}", region, pass ? "PASS" : "FAIL", bounded);
    }
}
