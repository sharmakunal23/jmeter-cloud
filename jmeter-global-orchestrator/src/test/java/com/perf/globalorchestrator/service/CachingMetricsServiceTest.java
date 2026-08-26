package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.config.CacheConfig;
import com.perf.globalorchestrator.domain.MetricsTimeseries;
import com.perf.globalorchestrator.domain.MetricsTimeseries.Series;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.repo.MetricsRollupRepository;
import com.perf.globalorchestrator.repo.MetricsTimeseriesRepository;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the terminal-only caching contract of
 * {@link CachingMetricsService} through the real Spring cache proxy (so the
 * {@code @Cacheable(condition=…)} SpEL is actually exercised), against an
 * in-memory {@link ConcurrentMapCacheManager}. No Redis, no DB — the gating is
 * provider-agnostic, and the underlying repositories are Mockito mocks whose
 * invocation counts are the assertion surface (a cache hit = the repo was NOT
 * re-invoked = no SQL).
 */
@SpringJUnitConfig
@DisplayName("CachingMetricsService — terminal-only caching (CACHE C-1)")
class CachingMetricsServiceTest {

    @Configuration
    @EnableCaching
    static class Config {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(
                    CacheConfig.CACHE_RUN_TIMESERIES, CacheConfig.CACHE_RUN_ROLLUP);
        }
        @Bean MetricsTimeseriesRepository timeseriesRepo() {
            return Mockito.mock(MetricsTimeseriesRepository.class);
        }
        @Bean MetricsRollupRepository rollupRepo() {
            return Mockito.mock(MetricsRollupRepository.class);
        }
        @Bean CachingMetricsService cachingMetricsService(
                MetricsTimeseriesRepository t, MetricsRollupRepository r) {
            return new CachingMetricsService(t, r, SETTLE);
        }
    }

    /** Settle margin under test — live runs trim this many trailing seconds. */
    private static final int SETTLE = 5;

    @Autowired CachingMetricsService service;
    @Autowired MetricsTimeseriesRepository timeseriesRepo;
    @Autowired MetricsRollupRepository rollupRepo;
    @Autowired CacheManager cacheManager;

    private static MetricsTimeseries sampleTs(String runId) {
        return new MetricsTimeseries(runId, 1, null, null,
                new Series(List.of(), List.of(), List.of(), Map.of()));
    }

    @BeforeEach
    void reset() {
        Mockito.reset(timeseriesRepo, rollupRepo);
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
        when(timeseriesRepo.timeseries(Mockito.anyString(), Mockito.anyBoolean(),
                        Mockito.nullable(Long.class), Mockito.anyInt()))
                .thenAnswer(i -> sampleTs(i.getArgument(0)));
        when(rollupRepo.rollupByLabel(Mockito.anyString()))
                .thenReturn(List.of(Map.of("label", "GET /a")));
    }

    @Test
    @DisplayName("terminal run: SQL runs once, second call is a cache hit")
    void terminalTimeseries_cached() {
        service.timeseries("runT", RunState.COMPLETED, false, null);
        service.timeseries("runT", RunState.COMPLETED, false, null);
        verify(timeseriesRepo, times(1)).timeseries("runT", false, null, 0);
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_TIMESERIES).get("runT:false:all")).isNotNull();
    }

    @Test
    @DisplayName("byRegion is part of the cache key: aggregate + region-split cache independently")
    void byRegionCachedIndependently() {
        // Same terminal run, two different byRegion flags → two SQL hits
        // (distinct keys), each then served from its own cache entry.
        service.timeseries("runB", RunState.COMPLETED, false, null);
        service.timeseries("runB", RunState.COMPLETED, false, null);
        service.timeseries("runB", RunState.COMPLETED, true, null);
        service.timeseries("runB", RunState.COMPLETED, true, null);
        verify(timeseriesRepo, times(1)).timeseries("runB", false, null, 0);
        verify(timeseriesRepo, times(1)).timeseries("runB", true, null, 0);
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_TIMESERIES).get("runB:false:all")).isNotNull();
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_TIMESERIES).get("runB:true:all")).isNotNull();
    }

    @Test
    @DisplayName("window is part of the cache key: whole-test + a 30m window cache independently")
    void windowCachedIndependently() {
        service.timeseries("runW", RunState.COMPLETED, false, null);     // whole test
        service.timeseries("runW", RunState.COMPLETED, false, null);
        service.timeseries("runW", RunState.COMPLETED, false, 1800L);    // last 30m
        service.timeseries("runW", RunState.COMPLETED, false, 1800L);
        verify(timeseriesRepo, times(1)).timeseries("runW", false, null, 0);
        verify(timeseriesRepo, times(1)).timeseries("runW", false, 1800L, 0);
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_TIMESERIES).get("runW:false:all")).isNotNull();
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_TIMESERIES).get("runW:false:1800")).isNotNull();
    }

    @Test
    @DisplayName("active run: every call hits SQL, nothing is cached")
    void activeTimeseries_bypassed() {
        service.timeseries("runA", RunState.RUNNING, false, null);
        service.timeseries("runA", RunState.RUNNING, false, null);
        verify(timeseriesRepo, times(2)).timeseries("runA", false, null, SETTLE);
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_TIMESERIES).get("runA:false:all")).isNull();
    }

    @Test
    @DisplayName("null state is treated as not-cacheable (bypass)")
    void nullState_bypassed() {
        service.timeseries("runN", null, false, null);
        service.timeseries("runN", null, false, null);
        verify(timeseriesRepo, times(2)).timeseries("runN", false, null, SETTLE);
    }

    @Test
    @DisplayName("rollup mirrors the timeseries gating: terminal cached, active bypassed")
    void rollupGating() {
        service.rollupByLabel("rollT", RunState.FAILED);
        service.rollupByLabel("rollT", RunState.FAILED);
        verify(rollupRepo, times(1)).rollupByLabel("rollT");

        service.rollupByLabel("rollA", RunState.DRAINING);
        service.rollupByLabel("rollA", RunState.DRAINING);
        verify(rollupRepo, times(2)).rollupByLabel("rollA");
    }

    @Test
    @DisplayName("distinct runIds are cached independently")
    void perRunIdIsolation() {
        service.timeseries("r1", RunState.COMPLETED, false, null);
        service.timeseries("r2", RunState.ABORTED, false, null);
        service.timeseries("r1", RunState.COMPLETED, false, null);
        service.timeseries("r2", RunState.ABORTED, false, null);
        verify(timeseriesRepo, times(1)).timeseries("r1", false, null, 0);
        verify(timeseriesRepo, times(1)).timeseries("r2", false, null, 0);
    }

    @Test
    @DisplayName("active-then-terminal: no cache while RUNNING, fills + hits once terminal")
    void transitionActiveToTerminal() {
        // Two RUNNING calls — both bypass, two SQL hits (settle-trimmed),
        // nothing cached.
        service.timeseries("flip", RunState.RUNNING, false, null);
        service.timeseries("flip", RunState.RUNNING, false, null);
        verify(timeseriesRepo, times(2)).timeseries("flip", false, null, SETTLE);

        // Run completes: first terminal call fills the cache (a 3rd SQL hit, now
        // un-trimmed at settle 0), second terminal call is served from cache.
        service.timeseries("flip", RunState.COMPLETED, false, null);
        service.timeseries("flip", RunState.COMPLETED, false, null);
        verify(timeseriesRepo, times(1)).timeseries("flip", false, null, 0);
    }
}
