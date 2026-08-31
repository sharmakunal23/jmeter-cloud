package com.perf.regionalorchestrator.provision;

/**
 * One dry-run registration check: can this regional actually create worker
 * Pods? A failed check reports what is wrong in {@code detail} — it is never
 * an HTTP error, so the hub can show the whole checklist at once.
 */
public record ProvisioningCheck(String name, boolean ok, String detail) {
}
