package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.config.CacheConfig;
import com.perf.globalorchestrator.domain.MetricsTimeseries;
import com.perf.globalorchestrator.domain.MetricsTimeseries.Series;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.domain.RunSummary;
import com.perf.globalorchestrator.repo.MetricsTarget;
import com.perf.globalorchestrator.repo.MetricsTimeseriesRepository;
import com.perf.globalorchestrator.repo.MetricsTimeseriesRepository.Query;
import com.perf.globalorchestrator.repo.RunMetricsRepository;
import com.perf.globalorchestrator.repo.RunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The terminal-only caching contract through the real Spring cache proxy (so
 * the {@code @Cacheable(condition=…)} SpEL runs), against an in-memory cache
 * and mocked repositories whose invocation counts are the assertion surface.
 * Also: a run with no resolvable group answers empty and never reaches SQL.
 */
@SpringJUnitConfig
@DisplayName("CachingMetricsService — terminal-only caching, group resolution")
class CachingMetricsServiceTest {

    @Configuration
    @EnableCaching
    static class Config {
        @Bean CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(CacheConfig.CACHE_RUN_TIMESERIES, CacheConfig.CACHE_RUN_ROLLUP, CacheConfig.CACHE_RUN_SUMMARY);
        }
        @Bean MetricsTimeseriesRepository timeseriesRepo() { return Mockito.mock(MetricsTimeseriesRepository.class); }
        @Bean RunMetricsRepository runMetricsRepo() { return Mockito.mock(RunMetricsRepository.class); }
        @Bean MetricsGroupResolver resolver() { return Mockito.mock(MetricsGroupResolver.class); }
        @Bean RunRepository runs() { return Mockito.mock(RunRepository.class); }
        @Bean CachingMetricsService cachingMetricsService(MetricsTimeseriesRepository t, RunMetricsRepository r,
                                                          MetricsGroupResolver g, RunRepository runs) {
            return new CachingMetricsService(t, r, g, runs, SETTLE);
        }
    }

    private static final int SETTLE = 20;
    private static final MetricsTarget CPS = new MetricsTarget("cps", "CPS", "CPS_METRICS", "CPS_METRICS_H", 4711L);

    @Autowired CachingMetricsService service;
    @Autowired MetricsTimeseriesRepository timeseriesRepo;
    @Autowired RunMetricsRepository runMetricsRepo;
    @Autowired MetricsGroupResolver resolver;
    @Autowired RunRepository runs;
    @Autowired CacheManager cacheManager;

    private static Run run(String id, RunState state) {
        Instant t = Instant.parse("2026-08-29T10:00:00Z");
        return new Run(id, "na-east", "b", null, "cps-pci", "t", state, null, t, t,
                state.isTerminal() ? t.plusSeconds(60) : null, false, null);
    }

    private static MetricsTimeseries sampleTs(String runId) {
        // One point: an EMPTY series is deliberately never cached (unless = …).
        return new MetricsTimeseries(runId, 15, 15L, 15L, new Series(
                List.of(new MetricsTimeseries.TimeseriesPoint(15L, 1.0)), List.of(), List.of(), Map.of()));
    }

    @BeforeEach
    void reset() {
        Mockito.reset(timeseriesRepo, runMetricsRepo, resolver, runs);
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
        when(runs.findByRunId(anyString())).thenAnswer(i -> Optional.of(run(i.getArgument(0),
                ((String) i.getArgument(0)).startsWith("live") ? RunState.RUNNING : RunState.COMPLETED)));
        when(resolver.resolve(any())).thenReturn(Optional.of(CPS));
        when(timeseriesRepo.timeseries(anyString(), any(), any(), any(), anyInt()))
                .thenAnswer(i -> sampleTs(i.getArgument(0)));
        when(runMetricsRepo.rollupByLabel(any(), any(), any(), anyInt())).thenReturn(List.of(Map.of("label", "GET /a")));
        when(runMetricsRepo.summary(anyString(), any(), any())).thenAnswer(i -> new RunSummary(i.getArgument(0), 15L, 60L,
                new RunSummary.Stats(null, 100, 1, 10, 1, 120, 200, 300, 900, 1500, 10), List.of()));
        when(timeseriesRepo.resolveRange(any(), any(), any(), anyInt())).thenAnswer(i -> i.getArgument(1));
    }

    @Test
    @DisplayName("terminal run: SQL runs once, the second call is a cache hit; live runs never cache and settle")
    void terminalCachedLiveNot() {
        service.timeseries("runT", RunState.COMPLETED, false, null);
        service.timeseries("runT", RunState.COMPLETED, false, null);
        verify(timeseriesRepo, times(1)).timeseries(eq("runT"), eq(CPS), any(), eq(new Query(false, false, null, null)), eq(0));
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_TIMESERIES).get("runT:false:all")).isNotNull();

        service.timeseries("liveL", RunState.RUNNING, false, 1800L);
        service.timeseries("liveL", RunState.RUNNING, false, 1800L);
        verify(timeseriesRepo, times(2)).timeseries(eq("liveL"), eq(CPS), any(), eq(new Query(false, false, null, 1800L)), eq(SETTLE));
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_TIMESERIES).get("liveL:false:1800")).isNull();
    }

    @Test
    @DisplayName("region, application, granularity and window are all part of the key")
    void keysAreDistinct() {
        service.timeseries("runB", RunState.COMPLETED, false, null);
        service.timeseries("runB", RunState.COMPLETED, true, null);
        service.timeseries("runB", RunState.COMPLETED, true, true, false, null, null, 60, 1800L);
        service.timeseries("runB", RunState.COMPLETED, true, true, false, null, null, 60, 1800L);
        service.timeseries("runB", RunState.COMPLETED, false, false, true, "TG1", 20, 15, null);
        ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
        verify(timeseriesRepo, times(4)).timeseries(eq("runB"), eq(CPS), any(), q.capture(), eq(0));
        assertThat(q.getAllValues()).containsExactly(
                new Query(false, false, null, null), new Query(true, false, null, null), new Query(true, true, 60, 1800L),
                new Query(false, false, true, "TG1", 20, 15, null));
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_TIMESERIES).get("runB:true:true:false:::60:1800")).isNotNull();
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_TIMESERIES).get("runB:false:false:true:TG1:20:15:all")).isNotNull();
    }

    @Test
    @DisplayName("a run without a resolvable group answers empty series and never queries the fact tables")
    void unresolvedIsEmpty() {
        when(resolver.resolve(any())).thenReturn(Optional.empty());
        MetricsTimeseries ts = service.timeseries("runU", RunState.RUNNING, false, null);
        assertThat(ts.series().tps()).isEmpty();
        assertThat(ts.bucketSize()).isEqualTo(15);
        assertThat(service.rollupByLabel("runU", RunState.RUNNING, null, null, 0)).isEmpty();
        assertThat(service.summary("runU", RunState.RUNNING, null).total().samples()).isZero();
        verify(timeseriesRepo, never()).timeseries(anyString(), any(), any(), any(), anyInt());
        verify(runMetricsRepo, never()).rollupByLabel(any(), any(), any(), anyInt());
        verify(runMetricsRepo, never()).summary(anyString(), any(), any());
    }

    @Test
    @DisplayName("rollup: cached for a terminal run, resolved through the group for the per-label table")
    void rollupCached() {
        service.rollupByLabel("runR", RunState.COMPLETED, null, null, 0);
        service.rollupByLabel("runR", RunState.COMPLETED, null, null, 0);
        verify(runMetricsRepo, times(1)).rollupByLabel(eq(CPS), any(), isNull(), eq(0));
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_ROLLUP).get("runR:all::0")).isNotNull();
        // A window, a prefix or a cap is a different key, and the prefix reaches SQL as an escaped pattern.
        service.rollupByLabel("runR", RunState.COMPLETED, 1800L, "TG_1", 10);
        verify(runMetricsRepo, times(1)).rollupByLabel(eq(CPS), any(), eq("TG\\_1%"), eq(10));
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_ROLLUP).get("runR:1800:TG_1:10")).isNotNull();
        // The whole run of a terminal run needs no range probe; a trailing window does.
        verify(timeseriesRepo, times(1)).resolveRange(eq(CPS), any(), eq(1800L), eq(0));
    }

    @Test
    @DisplayName("an empty series for a terminal run is never cached — the next call reads again once the rows land")
    void emptyIsNotCached() {
        when(timeseriesRepo.timeseries(anyString(), any(), any(), any(), anyInt()))
                .thenReturn(new MetricsTimeseries("runE", 15, null, null, Series.empty()));
        service.timeseries("runE", RunState.COMPLETED, false, null);
        service.timeseries("runE", RunState.COMPLETED, false, null);
        verify(timeseriesRepo, times(2)).timeseries(eq("runE"), eq(CPS), any(), any(), eq(0));
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_TIMESERIES).get("runE:false:all")).isNull();
        when(runMetricsRepo.rollupByLabel(any(), any(), any(), anyInt())).thenReturn(List.of());
        service.rollupByLabel("runE", RunState.COMPLETED, null, null, 0);
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_ROLLUP).get("runE:all::0")).isNull();
        when(runMetricsRepo.summary(anyString(), any(), any())).thenReturn(RunSummary.empty("runE"));
        service.summary("runE", RunState.COMPLETED, null);
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_SUMMARY).get("runE:all")).isNull();
    }

    @Test
    @DisplayName("summary: one ROLLUP statement, cached per window for a terminal run, the live read settles through the same range")
    void summaryCached() {
        RunSummary first = service.summary("runS", RunState.COMPLETED, null);
        service.summary("runS", RunState.COMPLETED, null);
        assertThat(first.total().samples()).isEqualTo(100);
        verify(runMetricsRepo, times(1)).summary(eq("runS"), eq(CPS), any());
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_SUMMARY).get("runS:all")).isNotNull();
        verify(timeseriesRepo, never()).resolveRange(any(), any(), any(), anyInt());

        service.summary("liveS", RunState.RUNNING, 1800L);
        service.summary("liveS", RunState.RUNNING, 1800L);
        verify(runMetricsRepo, times(2)).summary(eq("liveS"), eq(CPS), any());
        verify(timeseriesRepo, times(2)).resolveRange(eq(CPS), any(), eq(1800L), eq(SETTLE));
        assertThat(cacheManager.getCache(CacheConfig.CACHE_RUN_SUMMARY).get("liveS:1800")).isNull();
    }
}
