package com.perf.k8sorchestrator.service;

import com.perf.k8sorchestrator.client.LocalOrchestratorClient;
import com.perf.k8sorchestrator.client.LocalOrchestratorClient.LogsResult;
import com.perf.k8sorchestrator.config.CacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the terminal-member-only caching contract of
 * {@link CachingLogTailService} through the real Spring cache proxy, against an
 * in-memory cache. The underlying {@link LocalOrchestratorClient} is a Mockito
 * mock whose call count is the assertion surface (a cache hit = the local
 * orchestrator was NOT re-contacted).
 */
@SpringJUnitConfig
@DisplayName("CachingLogTailService — terminal-member-only log-tail caching (CACHE C-5)")
class CachingLogTailServiceTest {

    @Configuration
    @EnableCaching
    static class Config {
        @Bean CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(CacheConfig.CACHE_MEMBER_LOGS);
        }
        @Bean LocalOrchestratorClient localClient() {
            return Mockito.mock(LocalOrchestratorClient.class);
        }
        @Bean CachingLogTailService cachingLogTailService(LocalOrchestratorClient c) {
            return new CachingLogTailService(c);
        }
    }

    @Autowired CachingLogTailService service;
    @Autowired LocalOrchestratorClient localClient;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void reset() {
        Mockito.reset(localClient);
        cacheManager.getCache(CacheConfig.CACHE_MEMBER_LOGS).clear();
        when(localClient.getLogs(anyString(), anyInt(), anyString()))
                .thenReturn(new LogsResult(200, "frozen log body"));
    }

    @Test
    @DisplayName("terminal member: local orchestrator contacted once across two reads")
    void terminalMember_cached() {
        service.getLogs("runT", "wkr-1", "http://wkr-1:8080", "console", 200, true);
        service.getLogs("runT", "wkr-1", "http://wkr-1:8080", "console", 200, true);
        verify(localClient, times(1)).getLogs("http://wkr-1:8080", 200, "console");
    }

    @Test
    @DisplayName("active member: every read hits the local orchestrator (never cached)")
    void activeMember_bypassed() {
        service.getLogs("runA", "wkr-1", "http://wkr-1:8080", "console", 200, false);
        service.getLogs("runA", "wkr-1", "http://wkr-1:8080", "console", 200, false);
        verify(localClient, times(2)).getLogs("http://wkr-1:8080", 200, "console");
    }

    @Test
    @DisplayName("non-200 result (pod unreachable) is NOT cached, even for a terminal member")
    void nonOk_notCached() {
        when(localClient.getLogs(anyString(), anyInt(), anyString()))
                .thenReturn(new LogsResult(0, ""));
        service.getLogs("runT", "gone", "http://gone:8080", "console", 200, true);
        service.getLogs("runT", "gone", "http://gone:8080", "console", 200, true);
        verify(localClient, times(2)).getLogs("http://gone:8080", 200, "console");
    }

    @Test
    @DisplayName("key includes runId / stream / tail — reused pod across runs does not collide")
    void keyIsolation() {
        // Same workerId + podBaseUrl, different runId (pod reused for run B):
        // must NOT serve run A's cached tail to run B.
        service.getLogs("runA", "wkr-1", "http://wkr-1:8080", "console", 200, true);
        service.getLogs("runB", "wkr-1", "http://wkr-1:8080", "console", 200, true);
        // Different stream + different tail are independent entries too.
        service.getLogs("runA", "wkr-1", "http://wkr-1:8080", "jmeter", 200, true);
        service.getLogs("runA", "wkr-1", "http://wkr-1:8080", "console", 1000, true);
        verify(localClient, times(4)).getLogs(anyString(), anyInt(), anyString());

        // Re-reading the first (runA/console/200) is now a hit — still 4.
        service.getLogs("runA", "wkr-1", "http://wkr-1:8080", "console", 200, true);
        verify(localClient, times(4)).getLogs(anyString(), anyInt(), anyString());
    }
}
