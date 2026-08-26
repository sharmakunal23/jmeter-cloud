package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.aggregator.TumblingWindowAggregator;
import com.perf.orchestrator.buffer.MetricsDispatcher;
import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.io.SentinelWatcher;
import com.perf.orchestrator.io.JtlOffsetStore;
import com.perf.orchestrator.statemachine.TailerStateMachine;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Assembles the JTL → parse → aggregate → dispatch → state-machine wiring.
 *
 * <p>{@code TestRunManager} constructs one of these per test run with an
 * {@link OrchestratorConfig} reflecting the per-run paths and identity, plus
 * the process-wide singleton {@link MetricsDispatcher} that the state machine
 * offers envelopes to (and drains at end-of-run).
 *
 * <p>The {@link MetricsDispatcher} is a constructor parameter rather than a
 * field constructed inside this class — that's load-bearing: the dispatcher
 * (with its disk buffer + warm HTTP client to the metrics-consumer) is a
 * singleton shared across every run. Tests pass a recording fake; production
 * passes the {@code AsyncMetricsDispatcher} from the Spring context.
 *
 * <p>{@link #run()} blocks until the underlying {@link TailerStateMachine}
 * reaches its DONE state (sentinel observed, drain complete, or SIGTERM
 * received). The state machine drains the dispatch queue at end of run but
 * does NOT close the dispatcher — the orchestrator's shutdown hook owns the
 * singleton lifecycle.
 */
public class StreamingPipeline {

    private final TailerStateMachine machine;

    public StreamingPipeline(OrchestratorConfig config,
                             MetricsDispatcher dispatcher) {
        this(config, dispatcher, new LongAdder());
    }

    public StreamingPipeline(OrchestratorConfig config,
                             MetricsDispatcher dispatcher,
                             LongAdder offsetSaveFailures) {
        Objects.requireNonNull(config,             "config cannot be null");
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

        this.machine = new TailerStateMachine(
                config, stateStore, sentinel, aggregator, dispatcher);
    }

    /**
     * Runs the pipeline to completion. Blocks until the inner state machine
     * reaches DONE (sentinel observed and drained, or SIGTERM received).
     */
    public void run() {
        machine.run();
    }
}
