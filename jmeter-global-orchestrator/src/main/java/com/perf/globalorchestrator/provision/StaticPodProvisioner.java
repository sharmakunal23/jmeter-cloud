package com.perf.globalorchestrator.provision;

import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.PodState;
import com.perf.globalorchestrator.repo.PodRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The {@link PodProvisioner} for deployments where the operator deploys and
 * owns the workers ({@code PROVISIONING_MODE=STATIC}).
 *
 * <p>Reads answer from {@code globalOrchestrator.pod}, because there is no
 * daemon or cluster API to ask — the registry is the only thing this control
 * plane knows. Liveness there comes from heartbeats and from
 * {@link com.perf.globalorchestrator.sweep.StaticPodProbe}, and
 * {@code PodSweeper} owns the IDLE→LOST transition either way, so deriving from
 * {@link Pod#state()} keeps one staleness rule rather than inventing a second,
 * subtly different one here.
 *
 * <p><b>Every mutator throws {@link ProvisioningDisabledException}, and failing
 * loudly is the point.</b> A provisioner that silently no-op'd would report
 * "no containers exist", which {@code PodReconciler}'s row-first pass reads as
 * "every registry row is an orphan" — deleting the entire declared fleet on the
 * next boot. That reconciler is not wired in this mode, and this class refuses
 * rather than answering plausibly so a future caller cannot quietly reintroduce
 * the hazard.
 */
@Component
@ConditionalOnProvisioningMode(ProvisioningMode.STATIC)
public class StaticPodProvisioner implements PodProvisioner {

    private final PodRepository pods;

    public StaticPodProvisioner(PodRepository pods) {
        this.pods = pods;
    }

    // ── Mutators — all refused ──────────────────────────────────────────

    @Override
    public ProvisionResult createAndStart(PodSpec spec) {
        throw new ProvisioningDisabledException("create worker " + spec.podName());
    }

    @Override
    public void stopAndRemove(String region, String podName) {
        throw new ProvisioningDisabledException("stop and remove worker " + podName);
    }

    @Override
    public void stop(String region, String podName) {
        throw new ProvisioningDisabledException("stop worker " + podName);
    }

    @Override
    public void start(String region, String podName) {
        throw new ProvisioningDisabledException("start worker " + podName);
    }

    @Override
    public void restart(String region, String podName) {
        throw new ProvisioningDisabledException("restart worker " + podName);
    }

    // ── Read paths — answered from the pod registry ─────────────────────

    /** True when the control plane has a registry row for this pod. */
    @Override
    public boolean exists(String region, String podName) {
        return find(podName).isPresent();
    }

    /**
     * True when a registry row exists and the pod has not been swept to
     * {@link PodState#LOST}. For an operator-managed worker this is the
     * honest answer to "is it up" — the control plane's only evidence is
     * that the worker is still being seen.
     */
    @Override
    public boolean isRunning(String region, String podName) {
        return find(podName)
                .map(p -> p.state() != PodState.LOST)
                .orElse(false);
    }

    /**
     * Registry-derived view of the declared fleet. {@code status} is
     * {@code "running"} / {@code "unreachable"} rather than a Pod phase —
     * there is no cluster to report one.
     *
     * @param region nullable — when null, every region for the application
     */
    @Override
    public List<ProvisionedPod> listFor(String applicationId, String region) {
        List<Pod> rows = (region == null)
                ? pods.findAll().stream()
                        .filter(p -> applicationId != null && applicationId.equals(p.applicationId()))
                        .toList()
                : pods.findByApplicationAndRegion(applicationId, region);
        return rows.stream()
                .map(p -> new ProvisionedPod(
                        p.podId(),
                        p.applicationId(),
                        p.region(),
                        p.state() == PodState.LOST ? "unreachable" : "running",
                        // Declared workers have no provisionedAt until they
                        // report one; registeredAt is when the control plane
                        // first saw this pod, which is the closest honest value.
                        p.provisionedAt() != null ? p.provisionedAt() : p.registeredAt(),
                        p.imageDigest()))
                .toList();
    }

    /**
     * Always {@code null} — the control plane does not own the worker
     * image here, so it cannot say what "current" means. The interface
     * already defines null as "skip the image-mismatch check", which is
     * exactly right: an operator-managed rollout is not ours to police.
     */
    @Override
    public String currentImageDigest(String region) {
        return null;
    }

    /**
     * The URL the operator declared for this pod. Unlike the daemon-backed
     * provisioners there is no naming convention to synthesize from — an
     * externally deployed worker's address is only knowable from the
     * registry — so an unknown pod is a programming error, not a
     * computable default.
     */
    @Override
    public String baseUrlFor(String region, String podName) {
        return find(podName)
                .map(Pod::baseUrl)
                .orElseThrow(() -> new IllegalStateException(
                        "no registered baseUrl for worker " + podName
                        + "; operator-managed workers have no derivable address"));
    }

    private Optional<Pod> find(String podName) {
        return pods.findByPodId(podName);
    }
}
