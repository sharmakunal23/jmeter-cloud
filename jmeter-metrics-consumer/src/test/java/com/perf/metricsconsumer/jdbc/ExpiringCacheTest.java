package com.perf.metricsconsumer.jdbc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Single-flight loads, expiry, no caching of failures, bounded size. */
class ExpiringCacheTest {

    @Test
    void loads_once_per_key_and_serves_the_cached_value_until_it_expires() throws Exception {
        ExpiringCache<String, Integer> cache = new ExpiringCache<>(200, 10);
        AtomicInteger loads = new AtomicInteger();
        assertThat(cache.get("a", k -> loads.incrementAndGet())).isEqualTo(1);
        assertThat(cache.get("a", k -> loads.incrementAndGet())).isEqualTo(1);
        Thread.sleep(250);
        assertThat(cache.get("a", k -> loads.incrementAndGet())).isEqualTo(2);
    }

    @Test
    void a_failing_loader_caches_nothing() {
        ExpiringCache<String, Integer> cache = new ExpiringCache<>(60_000, 10);
        assertThatThrownBy(() -> cache.get("a", k -> { throw new IllegalStateException("db down"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cache.size()).isZero();
        assertThat(cache.get("a", k -> 7)).isEqualTo(7);
    }

    @Test
    void concurrent_misses_on_one_key_run_the_loader_once() throws Exception {
        ExpiringCache<String, Integer> cache = new ExpiringCache<>(60_000, 10);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<Future<Integer>>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> { go.await(); return cache.get("k", k -> { sleep(50); return loads.incrementAndGet(); }); }));
            }
            go.countDown();
            for (Future<Integer> f : futures) {
                assertThat(f.get()).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void grows_past_the_cap_only_until_the_next_load_evicts() {
        ExpiringCache<Integer, Integer> cache = new ExpiringCache<>(60_000, 3);
        for (int i = 0; i < 5; i++) cache.get(i, k -> k);
        assertThat(cache.size()).isLessThanOrEqualTo(3);
        cache.invalidate(4);
        assertThat(cache.get(4, k -> 44)).isEqualTo(44);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
