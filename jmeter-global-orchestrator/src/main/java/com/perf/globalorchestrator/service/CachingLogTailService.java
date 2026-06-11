package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.client.LocalOrchestratorClient;
import com.perf.globalorchestrator.client.LocalOrchestratorClient.LogsResult;
import com.perf.globalorchestrator.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * CACHE C-5 (2026-05-26) — caches the per-pod log tail that
 * {@code GET /runs/{runId}/members/{workerId}/logs} proxies from the local
 * orchestrator, but only for <b>terminal</b> members.
 *
 * <p>Why terminal-only: while a member is running its JMeter child appends to
 * the log ring buffer every second, so an active member's tail must always be
 * re-fetched live. Once the member reaches a terminal state ({@code COMPLETED}
 * / {@code FAILED} / {@code ABORTED} / {@code DRAINED}) its buffer is frozen, so
 * an operator re-opening a finished run to copy a stack trace can be served from
 * cache instead of round-tripping to the local orchestrator on every poll.
 *
 * <h2>Cache key — why {@code runId} is in it</h2>
 * The key is {@code runId|workerId|stream|tail}, not just
 * {@code workerId|stream|tail} as the original C-5 sketch had it. A pod
 * ({@code workerId}) can be <b>reused across runs</b> under the REUSE recycle
 * policy, so a workerId-only key would serve run A's cached logs for run B's
 * member. Keying on {@code runId} too is both collision-safe and <i>more</i>
 * correct than a live fetch — after a pod is reused, hitting the live buffer
 * would return the <i>new</i> run's logs, whereas the cache preserves the
 * terminal member's own frozen tail. Different {@code tail} values and
 * {@code stream}s are independent responses, so they're keyed separately
 * (caller passes the already-clamped {@code tail}).
 *
 * <h2>What is NOT cached</h2>
 * <ul>
 *   <li>Active members — {@code condition = "#terminal"} bypasses the cache.</li>
 *   <li>Non-200 results — {@code unless} drops them, so a "pod unreachable" (0)
 *       or "no buffer yet" (404) for a terminal member whose pod may be gone is
 *       never pinned; only a genuine 200 log body is cached.</li>
 * </ul>
 *
 * <p>Caching applies only when invoked through the Spring proxy — callers must
 * inject this bean (as {@code RunController} does), not the
 * {@link LocalOrchestratorClient} directly.
 */
@Service
public class CachingLogTailService {

    private final LocalOrchestratorClient localClient;

    public CachingLogTailService(LocalOrchestratorClient localClient) {
        this.localClient = localClient;
    }

    @Cacheable(cacheNames = CacheConfig.CACHE_MEMBER_LOGS,
               key = "#runId + '|' + #workerId + '|' + #stream + '|' + #tail",
               condition = "#terminal",
               unless = "#result == null || #result.statusCode() != 200")
    public LogsResult getLogs(String runId, String workerId, String podBaseUrl,
                              String stream, int tail, boolean terminal) {
        return localClient.getLogs(podBaseUrl, tail, stream);
    }
}
