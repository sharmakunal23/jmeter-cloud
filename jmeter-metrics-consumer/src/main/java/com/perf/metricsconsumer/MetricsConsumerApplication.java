package com.perf.metricsconsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Consumes {@code WorkerMetric} Avro records from
 * {@code jmeter.metrics.perSecond} and writes per-second rows into the
 * {@code jmetercloud_metrics} Postgres database.
 *
 * <p>Step 7 skeleton — the consumer logs each record at INFO and increments
 * a Micrometer counter; actual Postgres inserts wait on the partitioned
 * schema landing in Step 9.
 */
@SpringBootApplication
@EnableKafka
@EnableScheduling
public class MetricsConsumerApplication {
    public static void main(String[] args) {
        SpringApplication.run(MetricsConsumerApplication.class, args);
    }
}
