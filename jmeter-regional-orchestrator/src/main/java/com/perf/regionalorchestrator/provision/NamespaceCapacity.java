package com.perf.regionalorchestrator.provision;

/**
 * How many more workers this namespace's {@code ResourceQuota}s admit right
 * now — read live from the cluster, published on {@code GET /api/v1/capabilities}
 * so the hub can refuse a spin before a Pod is created. A {@code null}
 * dimension is not quota-bound.
 *
 * @param podsFree        {@code hard.pods - used.pods} over the tightest quota
 * @param memoryFreeMi    {@code requests.memory} headroom in MiB (the tighter of
 *                        requests/limits), null when memory is not quota-bound or
 *                        workers carry no memory resources
 * @param cpuFreeMillis   the same for CPU, in millicores
 * @param ephemeralFreeMi {@code requests/limits.ephemeral-storage} headroom in
 *                        MiB, null when it is not quota-bound or workers declare
 *                        no ephemeral-storage
 * @param workersFree     the number of workers that fit across every bound
 *                        dimension given this region's worker shape; null when
 *                        nothing bounds it
 */
public record NamespaceCapacity(Integer podsFree, Long memoryFreeMi, Long cpuFreeMillis,
                                Long ephemeralFreeMi, Integer workersFree) {

    public static final NamespaceCapacity UNBOUNDED = new NamespaceCapacity(null, null, null, null, null);
}
