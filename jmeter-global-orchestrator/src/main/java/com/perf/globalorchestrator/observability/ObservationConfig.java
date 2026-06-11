package com.perf.globalorchestrator.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * OBSERVABILITY Phase C — controls which observations turn into spans.
 *
 * <p>Spring Boot 3's auto-configured HTTP server observation produces one
 * span per request, regardless of how trivial that request is. With the
 * default 1% head-sampling rate that's fine, but it also means that 1%
 * of /actuator/health probes (one every 10 s × 5 services) take spans
 * from the budget the operator-targeted endpoints could have used.
 *
 * <p>The predicate below drops HTTP server observations whose path isn't
 * critical — they don't become spans and don't show up in Jaeger. JDBC,
 * Kafka, and any other observations still flow through unaffected.
 *
 * <p>Defensive note on the context match: Spring 6 servlet stack puts the
 * {@link HttpServletRequest} into {@link ServerRequestObservationContext}
 * as the {@code carrier}. We check both that the context is the expected
 * type AND that the carrier is non-null — Spring Boot creates observations
 * very early in the request, and some autoconfig paths construct the
 * context before the carrier is attached. Returning {@code true} for
 * those edge cases lets the observation through; the regular sampling
 * rate keeps the volume bounded.
 */
@Configuration
public class ObservationConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ObservationConfig.class);

    public ObservationConfig() {
        LOG.info("ObservationConfig wired — non-critical HTTP paths will be dropped from tracing. " +
                "Critical prefixes: {}", CriticalPaths.CRITICAL_PREFIXES);
    }

    @Bean
    public ObservationPredicate excludeNonCriticalHttpPaths() {
        return (name, context) -> {
            // 1. HTTP server observations — gated by CriticalPaths.
            if (context instanceof ServerRequestObservationContext serverCtx) {
                HttpServletRequest request = serverCtx.getCarrier();
                if (request == null) return true;
                return CriticalPaths.isCritical(request.getRequestURI());
            }
            // 2. Scheduled-task observations (timer-fired sweeps like
            //    `task pod-sweeper.sweep`, `task application-health-poller.poll-all`).
            //    Operator-asked 2026-05-25: these are noise — they're not
            //    user-initiated, run on a fixed cadence, and never explain
            //    why an HTTP request was slow. Drop them outright.
            if (context instanceof ScheduledTaskObservationContext) {
                return false;
            }
            // 3. Everything else (Kafka publish/consume, JDBC, @Observed
            //    application methods) flows through. Those carry the
            //    HTTP-triggered traceparent and complete the waterfall.
            return true;
        };
    }

    /**
     * OBSERVABILITY Phase D — registers Micrometer's {@link ObservedAspect}
     * so methods annotated with {@code @Observed} become AOP-wrapped span
     * boundaries. Without this bean the annotation is a no-op (Spring Boot
     * does NOT autoconfigure it because the dep is in spring-boot-starter-aop,
     * not in the tracing starter).
     *
     * <p>The aspect catches both checked + unchecked exceptions, tags the
     * span as ERROR, and rethrows — so a failing scaleUp shows up red in
     * Jaeger with the stacktrace as a span event.
     */
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }

    /**
     * OBSERVABILITY Phase G — enables Prometheus histogram buckets on every
     * timer so the observability dashboard can compute p95 / p99 via
     * {@code histogram_quantile()}. Without this, timers expose only
     * {@code _count} and {@code _sum} (means) and the dashboard would have
     * no percentile data.
     *
     * <p>The filter is scoped to timer-typed meters via Micrometer's
     * {@code configure()} hook — gauges and counters never call it, so
     * Prometheus output for those stays the same shape.
     *
     * <p>The buckets are Micrometer's default sequence (geometric, 0.001s
     * → 30s), which fits HTTP request latencies and the action timers
     * from {@code @Observed}. Override per-meter via additional
     * {@code MeterFilter}s if a specific timer needs different bounds.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> percentileHistogramCustomizer() {
        return registry -> registry.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        .build()
                        .merge(config);
            }
        });
    }
}
