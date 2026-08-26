package com.perf.metricsconsumer.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks when the last ingest envelope was processed and how many rows have
 * landed, feeding a 30-second INFO log and
 * {@link ConsumerHeartbeatHealthIndicator}. Together they are how a wedged
 * consumer gets detected — without them it presents only as the UI slowly
 * ceasing to update, with nothing obvious in the logs.
 *
 * <p>{@code lastBatchAtMillis} starts at the boot timestamp rather than zero,
 * so a fresh boot is not instantly stale.
 */
@Component
public class ConsumerHeartbeat {

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerHeartbeat.class);

    private final AtomicLong lastBatchAtMillis = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong totalRowsProcessed = new AtomicLong(0);
    private final AtomicLong totalBatchesProcessed = new AtomicLong(0);

    /** Called by {@code IngestController} after a successful writer commit. */
    public void markBatchProcessed(int rowsWritten) {
        lastBatchAtMillis.set(System.currentTimeMillis());
        totalRowsProcessed.addAndGet(Math.max(0, rowsWritten));
        totalBatchesProcessed.incrementAndGet();
    }

    /** Read by {@link ConsumerHeartbeatHealthIndicator}. */
    public Duration ageSinceLastBatch() {
        return Duration.ofMillis(System.currentTimeMillis() - lastBatchAtMillis.get());
    }

    public long totalRowsProcessed() {
        return totalRowsProcessed.get();
    }

    public long totalBatchesProcessed() {
        return totalBatchesProcessed.get();
    }
}
