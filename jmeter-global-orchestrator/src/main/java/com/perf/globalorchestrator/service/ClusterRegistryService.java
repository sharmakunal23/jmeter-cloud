package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.RegionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write half of the cluster registry, under the same lock the reservations
 * take (CLUSTER-CAPACITY). {@link GroupReservationService} guards the invariant
 * "SUM of the groups' reservations ≤ the cluster's MAX_WORKERS" from the
 * reservation side; this guards it from the <b>cluster</b> side, because a
 * check-then-write here without the lock loses the race the other side wins:
 * a shrink that reads 0 reserved, a concurrent reserve of 15 under a ceiling of
 * 20, and the shrink writing 10 leaves 15 reserved on a 10-worker cluster.
 * Both writers therefore serialise on {@code SELECT … FOR UPDATE} of the
 * {@code ORCH_REGION} row.
 *
 * <p>The validation chain stays <b>outside</b> these transactions — it makes
 * HTTP calls to the regional, and holding a row lock across a network call is
 * how a slow data center becomes a stuck registry.
 */
@Service
public class ClusterRegistryService {

    private final RegionRepository regions;
    private final GroupCapacityRepository capacity;
    private final PodRepository pods;

    public ClusterRegistryService(RegionRepository regions, GroupCapacityRepository capacity, PodRepository pods) {
        this.regions = regions;
        this.capacity = capacity;
        this.pods = pods;
    }

    /**
     * Applies an edit once the row is locked, re-reading the reservations under
     * that lock so the ceiling can never land below them.
     *
     * @return the reservations the cluster carries, for the response view
     */
    @Transactional
    public int update(String region, String label, String url, int maxWorkers, boolean revalidated) {
        regions.lockMaxWorkers(region).orElseThrow(() -> new ClusterGoneException(region));
        int reserved = capacity.reservedByRegion().getOrDefault(region, 0);
        if (maxWorkers < reserved) {
            throw new ShrinkBelowReservedException(region, maxWorkers, reserved);
        }
        regions.update(region, label, url, maxWorkers, revalidated);
        return reserved;
    }

    /**
     * Deregisters a cluster nothing references. The lock makes the guard
     * truthful: a reservation cannot be created for this cluster while it is
     * held, and a worker cannot exist without one. The FK is the backstop —
     * a child row that slipped in surfaces as the same 409, never a 500.
     */
    @Transactional
    public void delete(String region) {
        regions.lockMaxWorkers(region).orElseThrow(() -> new ClusterGoneException(region));
        int reservations = capacity.countByRegion(region);
        long workers = pods.regionCapacities().stream()
                .filter(c -> region.equals(c.region()))
                .map(com.perf.globalorchestrator.domain.RegionCapacity::totalPods)
                .findFirst().orElse(0L);
        if (reservations > 0 || workers > 0) {
            throw new ClusterInUseException(region, reservations, workers);
        }
        try {
            regions.delete(region);
        } catch (DataIntegrityViolationException stillReferenced) {
            throw new ClusterInUseException(region, reservations, workers);
        }
    }

    /** The cluster was deregistered by someone else while this call was in flight. */
    public static final class ClusterGoneException extends RuntimeException {
        public ClusterGoneException(String region) {
            super("region '" + region + "' is not a registered cluster");
        }
    }

    public static final class ShrinkBelowReservedException extends RuntimeException {
        public final int maxWorkers, reserved;
        public ShrinkBelowReservedException(String region, int maxWorkers, int reserved) {
            super("cannot shrink cluster '" + region + "' to " + maxWorkers
                    + " worker(s) while groups reserve " + reserved + "; lower their reservations first");
            this.maxWorkers = maxWorkers;
            this.reserved = reserved;
        }
    }

    public static final class ClusterInUseException extends RuntimeException {
        public final int reservations;
        public final long workers;
        public ClusterInUseException(String region, int reservations, long workers) {
            super("cluster '" + region + "' still holds " + reservations + " group reservation(s) and "
                    + workers + " worker(s); remove those first");
            this.reservations = reservations;
            this.workers = workers;
        }
    }
}
