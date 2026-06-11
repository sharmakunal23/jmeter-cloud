package com.perf.documentservice.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * OBSERVABILITY Phase C — drops non-critical HTTP server observations so
 * actuator probes don't consume the sampling budget. See the
 * global-orchestrator's ObservationConfig for the full design rationale.
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
            // 2. Scheduled-task observations dropped per operator
            //    feedback 2026-05-25 — see global-orch ObservationConfig
            //    for the rationale.
            if (context instanceof ScheduledTaskObservationContext) {
                return false;
            }
            return true;
        };
    }

    /**
     * OBSERVABILITY Phase G — enables Prometheus histogram buckets on
     * every timer so the dashboard can compute p95 / p99. See the
     * jmeter-global-orchestrator equivalent for the full rationale.
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
