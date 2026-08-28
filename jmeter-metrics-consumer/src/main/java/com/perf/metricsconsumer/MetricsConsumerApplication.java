package com.perf.metricsconsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the metrics-consumer on port 8083, which ingests
 * {@code WorkerMetricBatch} JSON envelopes at {@code POST /api/v1/ingest} and
 * lands per-second rows and rollup deltas in the Oracle {@code metrics} schema.
 */
@SpringBootApplication
@EnableScheduling
public class MetricsConsumerApplication {
    public static void main(String[] args) {
        SpringApplication.run(MetricsConsumerApplication.class, args);
    }
}
