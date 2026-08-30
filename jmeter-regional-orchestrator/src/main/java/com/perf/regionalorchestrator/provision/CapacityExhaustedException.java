package com.perf.regionalorchestrator.provision;

/**
 * The namespace's {@code ResourceQuota} cannot admit another worker Pod —
 * refused before the API server is asked, so a run fails with the quota's
 * numbers instead of a Pod that sits {@code Pending} until the hub gives up.
 * Mapped to {@code 409 CAPACITY_EXHAUSTED}.
 */
public class CapacityExhaustedException extends RuntimeException {
    public CapacityExhaustedException(String message) {
        super(message);
    }
}
