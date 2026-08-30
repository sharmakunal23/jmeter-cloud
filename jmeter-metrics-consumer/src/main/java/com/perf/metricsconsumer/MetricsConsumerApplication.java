package com.perf.metricsconsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the metrics-consumer on port 8083, which ingests
 * {@code WorkerMetricBatch} JSON envelopes at {@code POST /api/v1/ingest} and
 * lands 15-second rows in the group's fact table in {@code CARDZATE_DB_GRAF}, first write wins.
 */
@SpringBootApplication
@EnableScheduling
public class MetricsConsumerApplication {
    public static void main(String[] args) {
        SpringApplication.run(MetricsConsumerApplication.class, args);
    }
}
