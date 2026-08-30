package com.perf.globalorchestrator.provision;

import com.perf.globalorchestrator.repo.PodRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PodSpinService — a spun pod is LOST until the kubelet reports it ready")
class PodSpinServiceTest {

    private final PodRepository pods = mock(PodRepository.class);
    private final PodNameAllocator allocator = mock(PodNameAllocator.class);
    private final PodProvisioner provisioner = mock(PodProvisioner.class);

    @Test
    void readyPodBecomesIdle() {
        when(allocator.allocate("payments", "na-east")).thenReturn("payments-na-east-worker-1");
        when(provisioner.baseUrlFor("na-east", "payments-na-east-worker-1")).thenReturn("http://payments-na-east-worker-1.workers:8080");
        when(provisioner.createAndStart(any())).thenReturn(new ProvisionResult("http://payments-na-east-worker-1.workers:8080", "img", Instant.now()));
        when(provisioner.isReady("na-east", "payments-na-east-worker-1")).thenReturn(false, true);

        PodSpinService.SpinResult r = new PodSpinService(pods, allocator, provisioner, 30_000).spin("payments", "na-east");

        assertThat(r.ready()).isTrue();
        verify(pods).registerStarting("payments-na-east-worker-1", "na-east", "http://payments-na-east-worker-1.workers:8080", "payments");
        verify(pods).heartbeat("payments-na-east-worker-1");
        verify(pods, never()).register(any(), any(), any(), any());
    }

    @Test
    void slowPodStaysLostForTheLivenessProbe() {
        when(allocator.allocate("payments", "na-east")).thenReturn("payments-na-east-worker-2");
        when(provisioner.baseUrlFor(any(), any())).thenReturn("http://x:8080");
        when(provisioner.createAndStart(any())).thenReturn(new ProvisionResult("http://x:8080", "img", Instant.now()));
        when(provisioner.isReady(any(), any())).thenReturn(false);

        PodSpinService.SpinResult r = new PodSpinService(pods, allocator, provisioner, 1).spin("payments", "na-east");

        assertThat(r.ready()).isFalse();
        verify(pods, never()).heartbeat(any());
    }

    @Test
    void aNameTakenConcurrentlyIsAllocatedAgain() {
        when(allocator.allocate("payments", "na-east"))
                .thenReturn("payments-na-east-worker-1", "payments-na-east-worker-2");
        when(provisioner.baseUrlFor(any(), any())).thenReturn("http://x:8080");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("taken"))
                .when(pods).registerStarting(org.mockito.ArgumentMatchers.eq("payments-na-east-worker-1"), any(), any(), any());

        String name = new PodSpinService(pods, allocator, provisioner, 1).reserve("payments", "na-east");

        assertThat(name).isEqualTo("payments-na-east-worker-2");
        verify(pods).registerStarting("payments-na-east-worker-2", "na-east", "http://x:8080", "payments");
    }

    @Test
    void aFailedCreateRollsThePlaceholderBack() {
        when(allocator.allocate(any(), any())).thenReturn("payments-na-east-worker-3");
        when(provisioner.baseUrlFor(any(), any())).thenReturn("http://x:8080");
        when(provisioner.createAndStart(any())).thenThrow(new RuntimeException("cluster API refused"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new PodSpinService(pods, allocator, provisioner, 1).spin("payments", "na-east"))
                .hasMessageContaining("cluster API refused");
        verify(pods).deleteByPodId("payments-na-east-worker-3");
    }
}
