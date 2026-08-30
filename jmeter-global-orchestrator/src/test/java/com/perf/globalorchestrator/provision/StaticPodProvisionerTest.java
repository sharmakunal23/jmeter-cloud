package com.perf.globalorchestrator.provision;

import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.PodSource;
import com.perf.globalorchestrator.domain.PodState;
import com.perf.globalorchestrator.repo.PodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DisplayName("StaticPodProvisioner — refuses every mutation, answers reads from the registry")
class StaticPodProvisionerTest {

    private static final Instant REGISTERED = Instant.parse("2026-07-27T09:00:00Z");
    private static final String POD = "payments-na-east-worker-1";

    private PodRepository pods;
    private StaticPodProvisioner provisioner;

    @BeforeEach
    void setUp() {
        pods = mock(PodRepository.class);
        provisioner = new StaticPodProvisioner(pods);
    }

    // ── Mutators ────────────────────────────────────────────────────────

    @Test
    @DisplayName("createAndStart is refused — and never touches the registry")
    void createAndStartRefused() {
        PodSpec spec = new PodSpec(POD, "cps", "na-east");
        assertThatThrownBy(() -> provisioner.createAndStart(spec))
                .isInstanceOf(ProvisioningDisabledException.class)
                .hasMessageContaining(POD);
        verifyNoMoreInteractions(pods);
    }

    @Test
    @DisplayName("every lifecycle mutator is refused — a missed controller guard still 409s, "
            + "and nothing can silently no-op its way into looking like a healthy provisioner")
    void allMutatorsRefused() {
        assertThatThrownBy(() -> provisioner.stopAndRemove("na-east", POD))
                .isInstanceOf(ProvisioningDisabledException.class);
        assertThatThrownBy(() -> provisioner.stop("na-east", POD))
                .isInstanceOf(ProvisioningDisabledException.class);
        assertThatThrownBy(() -> provisioner.start("na-east", POD))
                .isInstanceOf(ProvisioningDisabledException.class);
        assertThatThrownBy(() -> provisioner.restart("na-east", POD))
                .isInstanceOf(ProvisioningDisabledException.class);
        verifyNoMoreInteractions(pods);
    }

    // ── Reads ───────────────────────────────────────────────────────────

    @Test
    void existsReflectsTheRegistry() {
        when(pods.findByPodId(POD)).thenReturn(Optional.of(pod(PodState.IDLE)));
        when(pods.findByPodId("ghost")).thenReturn(Optional.empty());

        assertThat(provisioner.exists("na-east", POD)).isTrue();
        assertThat(provisioner.exists("na-east", "ghost")).isFalse();
    }

    @Test
    @DisplayName("isRunning is true while the pod is still being seen, false once swept LOST — "
            + "PodSweeper stays the single owner of the staleness rule")
    void isRunningDerivesFromSweptState() {
        when(pods.findByPodId(POD)).thenReturn(Optional.of(pod(PodState.IDLE)));
        assertThat(provisioner.isRunning("na-east", POD)).isTrue();

        when(pods.findByPodId(POD)).thenReturn(Optional.of(pod(PodState.LOST)));
        assertThat(provisioner.isRunning("na-east", POD)).isFalse();
    }

    @Test
    @DisplayName("an unknown pod is not running — this is what lets a drain release a zombie "
            + "binding instead of blocking the undeclare forever")
    void isRunningFalseForUnknownPod() {
        when(pods.findByPodId("ghost")).thenReturn(Optional.empty());
        assertThat(provisioner.isRunning("na-east", "ghost")).isFalse();
    }

    @Test
    void nullAndBlankPodNamesAreNotRunning() {
        when(pods.findByPodId(null)).thenReturn(Optional.empty());
        when(pods.findByPodId("")).thenReturn(Optional.empty());
        assertThat(provisioner.isRunning("na-east", null)).isFalse();
        assertThat(provisioner.isRunning("na-east", "")).isFalse();
    }

    @Test
    @DisplayName("listFor with a region delegates to the region-scoped query")
    void listForRegionScoped() {
        when(pods.findByGroupAndRegion("cps", "na-east"))
                .thenReturn(List.of(pod(PodState.IDLE)));

        List<ProvisionedPod> found = provisioner.listFor("cps", "na-east");

        assertThat(found).singleElement().satisfies(p -> {
            assertThat(p.podName()).isEqualTo(POD);
            assertThat(p.region()).isEqualTo("na-east");
            assertThat(p.status()).isEqualTo("running");
            assertThat(p.startedAt()).isEqualTo(REGISTERED);
        });
    }

    @Test
    @DisplayName("listFor with a null region spans the application and marks LOST as unreachable")
    void listForAllRegions() {
        when(pods.findAll()).thenReturn(List.of(
                pod(PodState.LOST),
                new Pod("other-app-worker-1", "na-west", "http://other:8080",
                        PodState.IDLE, REGISTERED, REGISTERED, "otherApp", 0, null, null,
                        PodSource.STATIC)));

        List<ProvisionedPod> found = provisioner.listFor("cps", null);

        assertThat(found).singleElement().satisfies(p -> {
            assertThat(p.podName()).isEqualTo(POD);
            assertThat(p.status()).isEqualTo("unreachable");
        });
    }

    @Test
    @DisplayName("currentImageDigest is null — the interface's documented 'skip the image check', "
            + "which is right when an operator owns the rollout")
    void currentImageDigestIsNull() {
        assertThat(provisioner.currentImageDigest("na-east")).isNull();
    }

    @Test
    void baseUrlForReturnsTheDeclaredAddress() {
        when(pods.findByPodId(POD)).thenReturn(Optional.of(pod(PodState.IDLE)));
        assertThat(provisioner.baseUrlFor("na-east", POD)).isEqualTo("http://payments-na-east-worker-1:8080");
    }

    @Test
    @DisplayName("baseUrlFor throws for an unknown pod — there is no naming convention to "
            + "synthesize an externally deployed worker's address from")
    void baseUrlForUnknownPodThrows() {
        when(pods.findByPodId("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> provisioner.baseUrlFor("na-east", "ghost"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ghost");
    }

    private static Pod pod(PodState state) {
        return new Pod(POD, "na-east", "http://payments-na-east-worker-1:8080",
                state, REGISTERED, REGISTERED, "cps", 3, null, null, PodSource.STATIC);
    }
}
