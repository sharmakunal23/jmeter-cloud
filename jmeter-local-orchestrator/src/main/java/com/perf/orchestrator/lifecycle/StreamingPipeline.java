package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.aggregator.TumblingWindowAggregator;
import com.perf.orchestrator.buffer.MetricsDispatcher;
import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.io.SentinelWatcher;
import com.perf.orchestrator.io.JtlOffsetStore;
import com.perf.orchestrator.kafka.MetricPublisher;
import com.perf.orchestrator.statemachine.TailerStateMachine;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Assembles the JTL → parse → aggregate → publish → state-machine wiring.
 *
 * <p>{@code TestRunManager} constructs one of these per test run with an
 * {@link OrchestratorConfig} reflecting the per-run paths and identity, plus
 * the process-wide singleton {@link MetricPublisher} that the state machine
 * delivers to (and {@link MetricPublisher#flush() flushes} at end-of-run).
 *
 * <p>The {@link MetricPublisher} is a constructor parameter rather than a
 * field constructed inside this class — that's load-bearing now that the
 * publisher is a singleton (one warm Kafka producer per orchestrator
 * process). Tests pass a recording fake; production passes the
 * {@code KafkaMetricPublisher} pre-published into the Spring context.
 *
 * <p>{@link #run()} blocks until the underlying {@link TailerStateMachine}
 * reaches its DONE state (sentinel observed, drain complete, or SIGTERM
 * received). The state machine flushes the publisher at end of run but does
 * NOT close it — the orchestrator's shutdown hook owns the singleton lifecycle.
 */
public class StreamingPipeline {

    private final TailerStateMachine machine;

    public StreamingPipeline(OrchestratorConfig config,
                             MetricPublisher publisher,
                             MetricsDispatcher dispatcher) {
        this(config, publisher, dispatcher, new LongAdder());
    }

    public StreamingPipeline(OrchestratorConfig config,
                             MetricPublisher publisher,
                             MetricsDispatcher dispatcher,
                             LongAdder offsetSaveFailures) {
        Objects.requireNonNull(config,             "config cannot be null");
        Objects.requireNonNull(publisher,          "publisher cannot be null");
        Objects.requireNonNull(dispatcher,         "dispatcher cannot be null");
        Objects.requireNonNull(offsetSaveFailures, "offsetSaveFailures cannot be null");

        JtlOffsetStore stateStore = new JtlOffsetStore(
                Path.of(config.getStateFilePath()), offsetSaveFailures);

        SentinelWatcher sentinel = new SentinelWatcher(
                Path.of(config.getSentinelPath()));

        TumblingWindowAggregator aggregator = new TumblingWindowAggregator(
                config.getPodName(),
                config.getTestRegion(),
                config.getRunId(),
                config.getGracePeriodSeconds(),
                config.isUseThreadName(),
                config.getJoinedAtSecond());

        String topic = config.getKafkaTopic();
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException(
                    "config.kafkaTopic must be set on the per-run config — " +
                    "global-orch stamps it in fanOut for registered apps; " +
                    "for legacy untagged runs " +
                    "the orchestrator's KAFKA_TOPIC env default applies");
        }

        this.machine = new TailerStateMachine(
                config, stateStore, sentinel, aggregator, publisher, dispatcher, topic);
    }

    /**
     * Runs the pipeline to completion. Blocks until the inner state machine
     * reaches DONE (sentinel observed and drained, or SIGTERM received).
     */
    public void run() {
        machine.run();
    }
}
