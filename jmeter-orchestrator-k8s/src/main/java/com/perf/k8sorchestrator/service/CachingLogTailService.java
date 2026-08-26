package com.perf.k8sorchestrator.service;

import com.perf.k8sorchestrator.client.LocalOrchestratorClient;
import com.perf.k8sorchestrator.client.LocalOrchestratorClient.LogsResult;
import com.perf.k8sorchestrator.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Caches the per-pod log tail behind
 * {@code GET /runs/{runId}/members/{workerId}/logs}, but only for
 * <b>terminal</b> members — a running member's JMeter child appends every
 * second, so its tail must always be fetched live, while a finished member's
 * buffer is frozen and can be served from cache.
 *
 * <p><b>The key is {@code runId|workerId|stream|tail}, and the {@code runId} is
 * load-bearing.</b> A pod can be reused across runs under the REUSE recycle
 * policy, so a workerId-only key would serve run A's logs for run B. Including
 * runId is not just collision-safe but <i>more</i> correct than a live fetch:
 * after reuse, the live buffer holds the new run's logs, while the cache still
 * has the terminal member's own tail.
 *
 * <p>Active members bypass the cache via {@code condition}, and non-200 results
 * are dropped by {@code unless} — so an unreachable pod or a 404 for a terminal
 * member whose pod is gone is never pinned.
 *
 * <p>Caching only applies through the Spring proxy: callers must inject this
 * bean, not {@link LocalOrchestratorClient} directly.
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
