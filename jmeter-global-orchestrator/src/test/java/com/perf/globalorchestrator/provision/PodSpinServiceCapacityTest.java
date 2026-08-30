package com.perf.globalorchestrator.provision;

import com.perf.globalorchestrator.repo.PodRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The up-front capacity check (Track 8): a region's reported headroom refuses a larger shortfall; unknown headroom passes. */
class PodSpinServiceCapacityTest {

    @Test
    @DisplayName("assertCapacity refuses a shortfall above the region's reported headroom and passes when unknown")
    void assertCapacity() {
        PodProvisioner provisioner = mock(PodProvisioner.class);
        when(provisioner.availableWorkers("na-east")).thenReturn(2);
        when(provisioner.availableWorkers("na-west")).thenReturn(null);
        PodSpinService svc = new PodSpinService(mock(PodRepository.class), mock(PodNameAllocator.class), provisioner, 1000L);
        assertThatCode(() -> svc.assertCapacity("na-east", 2)).doesNotThrowAnyException();
        assertThatThrownBy(() -> svc.assertCapacity("na-east", 3))
                .isInstanceOf(PodSpinService.RegionCapacityExceededException.class)
                .hasMessageContaining("na-east can schedule 2 more worker(s)")
                .hasMessageContaining("3 needed");
        assertThatCode(() -> svc.assertCapacity("na-west", 50)).doesNotThrowAnyException();
    }
}
