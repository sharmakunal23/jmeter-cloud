package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.GroupCapacity;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GroupReservationService — the cluster invariants on a reservation write")
class GroupReservationServiceTest {

    private RegionRepository regions;
    private GroupCapacityRepository capacity;
    private GroupReservationService service;

    @BeforeEach
    void setUp() {
        regions = mock(RegionRepository.class);
        capacity = mock(GroupCapacityRepository.class);
        service = new GroupReservationService(regions, capacity, 2);
    }

    private static GroupCapacity row(String groupId, String region, int max) {
        return new GroupCapacity(groupId, region, max, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("a reservation that fits under the cluster ceiling is written")
    void reserveHappyPath() {
        when(regions.lockMaxWorkers("na-east")).thenReturn(Optional.of(20));
        when(capacity.find("cps", "na-east"))
                .thenReturn(Optional.of(row("cps", "na-east", 4)))
                .thenReturn(Optional.of(row("cps", "na-east", 12)));
        when(capacity.sumReservedForRegionExcluding("na-east", "cps")).thenReturn(8);

        GroupCapacity written = service.reserve("cps", "na-east", 12);

        assertThat(written.maxAvailable()).isEqualTo(12);
        verify(capacity).upsert("cps", "na-east", 12);
    }

    @Test
    @DisplayName("an unregistered cluster is refused before anything is written")
    void unregisteredCluster() {
        when(regions.lockMaxWorkers("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reserve("cps", "ghost", 1))
                .isInstanceOf(GroupReservationService.ClusterNotRegisteredException.class)
                .hasMessageContaining("ghost");
        verify(capacity, never()).upsert(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("a group's 3rd cluster is refused (maxClustersPerGroup=2)")
    void groupClusterLimit() {
        when(regions.lockMaxWorkers("na-south")).thenReturn(Optional.of(20));
        when(capacity.find("cps", "na-south")).thenReturn(Optional.empty());
        when(capacity.countByGroupId("cps")).thenReturn(2);

        assertThatThrownBy(() -> service.reserve("cps", "na-south", 1))
                .isInstanceOf(GroupReservationService.GroupClusterLimitException.class)
                .hasMessageContaining("2 cluster(s)");
        verify(capacity, never()).upsert(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("re-reserving on an already-attached cluster never trips the cluster count")
    void existingRowSkipsTheLimit() {
        when(regions.lockMaxWorkers("na-east")).thenReturn(Optional.of(20));
        when(capacity.find("cps", "na-east")).thenReturn(Optional.of(row("cps", "na-east", 5)));
        when(capacity.sumReservedForRegionExcluding("na-east", "cps")).thenReturn(0);

        service.reserve("cps", "na-east", 6);
        verify(capacity, never()).countByGroupId(anyString());
        verify(capacity).upsert("cps", "na-east", 6);
    }

    @Test
    @DisplayName("oversubscription is refused with the cluster's numbers")
    void clusterCapacityExceeded() {
        when(regions.lockMaxWorkers("na-east")).thenReturn(Optional.of(20));
        when(capacity.find("demo", "na-east")).thenReturn(Optional.of(row("demo", "na-east", 0)));
        when(capacity.sumReservedForRegionExcluding("na-east", "demo")).thenReturn(15);

        assertThatThrownBy(() -> service.reserve("demo", "na-east", 6))
                .isInstanceOf(GroupReservationService.ClusterCapacityExceededException.class)
                .hasMessageContaining("other groups hold 15 of its 20")
                .hasMessageContaining("at most 5 available");
        verify(capacity, never()).upsert(anyString(), anyString(), anyInt());
    }
}
