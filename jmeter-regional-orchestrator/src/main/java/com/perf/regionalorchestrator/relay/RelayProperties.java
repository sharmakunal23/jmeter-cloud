package com.perf.regionalorchestrator.relay;

/**
 * Timeouts for one relayed worker call. {@code readTimeoutMs} bounds how long
 * the hub's calling thread can be held by a slow worker; the worker's own
 * {@code POST /api/v1/test} answers in well under a second, so anything past
 * a few seconds is a stuck worker, not a slow one.
 */
public record RelayProperties(long connectTimeoutMs, long readTimeoutMs) {
}
