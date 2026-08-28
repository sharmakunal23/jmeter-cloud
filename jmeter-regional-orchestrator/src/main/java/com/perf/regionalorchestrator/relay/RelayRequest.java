package com.perf.regionalorchestrator.relay;

/**
 * One call to forward: {@code method} {@code subPath}[?{@code query}] on the
 * worker named {@code podName}, with the caller's body, content type,
 * {@code X-Actor} and {@code X-Run-Id} carried through verbatim.
 */
public record RelayRequest(
        String podName,
        String method,
        String subPath,
        String query,
        byte[] body,
        String contentType,
        String actor,
        String runId) {
}
