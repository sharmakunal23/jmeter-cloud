package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.RegionCapacity;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ClusterRegistryService — the cluster side of the ceiling invariant, under the region-row lock")
class ClusterRegistryServiceTest {

    private RegionRepository regions;
    private GroupCapacityRepository capacity;
    private PodRepository pods;
    private ClusterRegistryService service;

    @BeforeEach
    void setUp() {
        regions = mock(RegionRepository.class);
        capacity = mock(GroupCapacityRepository.class);
        pods = mock(PodRepository.class);
        service = new ClusterRegistryService(regions, capacity, pods);
    }

    @Test
    @DisplayName("the lock is taken BEFORE the reservations are read — that ordering is the whole guarantee")
    void locksBeforeReading() {
        when(regions.lockMaxWorkers("na-east")).thenReturn(Optional.of(20));
        when(capacity.reservedByRegion()).thenReturn(Map.of("na-east", 8));

        assertThat(service.update("na-east", "NA East", "http://na-east:30088", 12, false)).isEqualTo(8);

        var order = inOrder(regions, capacity);
        order.verify(regions).lockMaxWorkers("na-east");
        order.verify(capacity).reservedByRegion();
        order.verify(regions).update("na-east", "NA East", "http://na-east:30088", 12, false);
    }

    @Test
    @DisplayName("a shrink under the reservations read inside the lock is refused — nothing is written")
    void shrinkBelowReservedIsRefused() {
        when(regions.lockMaxWorkers("na-east")).thenReturn(Optional.of(20));
        // What a concurrent reserve committed while this call waited on the lock.
        when(capacity.reservedByRegion()).thenReturn(Map.of("na-east", 15));

        assertThatThrownBy(() -> service.update("na-east", "NA East", "http://na-east:30088", 10, false))
                .isInstanceOf(ClusterRegistryService.ShrinkBelowReservedException.class)
                .hasMessageContaining("groups reserve 15");

        verify(regions, never()).update(anyString(), anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("a cluster deregistered while the call was in flight is 'gone', not a silent no-op")
    void updateOnAVanishedCluster() {
        when(regions.lockMaxWorkers("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("ghost", "Ghost", "http://ghost:30088", 5, false))
                .isInstanceOf(ClusterRegistryService.ClusterGoneException.class);
        verify(regions, never()).update(anyString(), anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("delete refuses while a reservation or a worker references the cluster")
    void deleteGuards() {
        when(regions.lockMaxWorkers("na-east")).thenReturn(Optional.of(20));
        when(capacity.countByRegion("na-east")).thenReturn(2);
        when(pods.regionCapacities()).thenReturn(List.of(new RegionCapacity("na-east", 3, 3, 0)));

        assertThatThrownBy(() -> service.delete("na-east"))
                .isInstanceOf(ClusterRegistryService.ClusterInUseException.class)
                .hasMessageContaining("2 group reservation(s) and 3 worker(s)");
        verify(regions, never()).delete(anyString());
    }

    @Test
    @DisplayName("an unreferenced cluster deletes; a child row that slipped in surfaces as the same 409, never a 500")
    void deleteHappyPathAndFkBackstop() {
        when(regions.lockMaxWorkers("na-east")).thenReturn(Optional.of(20));
        when(capacity.countByRegion("na-east")).thenReturn(0);
        when(pods.regionCapacities()).thenReturn(List.of());

        service.delete("na-east");
        verify(regions).delete("na-east");

        when(regions.delete("na-east")).thenThrow(new DataIntegrityViolationException("ORA-02292 child record found"));
        assertThatThrownBy(() -> service.delete("na-east"))
                .isInstanceOf(ClusterRegistryService.ClusterInUseException.class);
    }
}
