package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.GroupCapacity;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.RegionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a group's reservation on one cluster ({@code ORCH_GROUP_CAPACITY.MAX_AVAILABLE})
 * under the cluster's invariants (CLUSTER-CAPACITY): the cluster must be
 * registered, a group holds at most {@code maxClustersPerGroup} clusters, and
 * the sum of every group's reservations never exceeds the cluster's
 * {@code MAX_WORKERS}. One transaction, serialised per cluster by
 * {@code SELECT … FOR UPDATE} on the {@code ORCH_REGION} row — two concurrent
 * reservations cannot oversubscribe.
 */
@Service
public class GroupReservationService {

    private final RegionRepository regions;
    private final GroupCapacityRepository capacity;
    private final int maxClustersPerGroup;

    public GroupReservationService(RegionRepository regions, GroupCapacityRepository capacity,
                                   @Value("${globalOrchestrator.capacity.maxClustersPerGroup:2}") int maxClustersPerGroup) {
        this.regions = regions;
        this.capacity = capacity;
        this.maxClustersPerGroup = Math.max(1, maxClustersPerGroup);
    }

    public int maxClustersPerGroup() {
        return maxClustersPerGroup;
    }

    /** @return the written row */
    @Transactional
    public GroupCapacity reserve(String groupId, String region, int requested) {
        int maxWorkers = regions.lockMaxWorkers(region)
                .orElseThrow(() -> new ClusterNotRegisteredException(region));
        boolean newRow = capacity.find(groupId, region).isEmpty();
        if (newRow && capacity.countByGroupId(groupId) >= maxClustersPerGroup) {
            throw new GroupClusterLimitException(groupId, maxClustersPerGroup);
        }
        int reservedByOthers = capacity.sumReservedForRegionExcluding(region, groupId);
        if (reservedByOthers + requested > maxWorkers) {
            throw new ClusterCapacityExceededException(region, maxWorkers, reservedByOthers, requested);
        }
        capacity.upsert(groupId, region, requested);
        return capacity.find(groupId, region)
                .orElseThrow(() -> new IllegalStateException("upsert produced no row"));
    }

    public static final class ClusterNotRegisteredException extends RuntimeException {
        public final String region;
        public ClusterNotRegisteredException(String region) {
            super("region '" + region + "' is not a registered cluster — register it first "
                    + "(POST /api/v1/regions)");
            this.region = region;
        }
    }

    public static final class GroupClusterLimitException extends RuntimeException {
        public final int maxClusters;
        public GroupClusterLimitException(String groupId, int maxClusters) {
            super("group '" + groupId + "' already reserves capacity on " + maxClusters
                    + " cluster(s) — the limit; remove one before attaching another");
            this.maxClusters = maxClusters;
        }
    }

    public static final class ClusterCapacityExceededException extends RuntimeException {
        public final int maxWorkers, reservedByOthers, requested;
        public ClusterCapacityExceededException(String region, int maxWorkers, int reservedByOthers, int requested) {
            super("cluster '" + region + "' cannot reserve " + requested + " worker(s): other groups hold "
                    + reservedByOthers + " of its " + maxWorkers
                    + " — at most " + Math.max(0, maxWorkers - reservedByOthers) + " available to reserve");
            this.maxWorkers = maxWorkers;
            this.reservedByOthers = reservedByOthers;
            this.requested = requested;
        }
    }
}
