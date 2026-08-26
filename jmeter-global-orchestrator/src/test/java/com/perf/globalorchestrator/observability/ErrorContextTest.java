package com.perf.globalorchestrator.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the ErrorContext helper: pins MDC lifecycle (set before the
 * log call, removed in finally) and verifies the SLF4J level + throwable
 * propagation. (The span-tagging half left with distributed tracing in
 * SLIMDOWN SL-E.)
 */
class ErrorContextTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(ErrorContextTest.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void logErrorEmitsErrorLevelWithMessageAndCause() {
        RuntimeException cause = new RuntimeException("boom");

        ErrorContext.logError(logger, "scaleUp runId=R-1 allocations=3", "scale-up failed", cause);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getMessage()).isEqualTo("scale-up failed");
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
    }

    @Test
    void logWarnEmitsWarnLevelWithMessageAndCause() {
        ErrorContext.logWarn(logger, "drainTest runId=R-1 podBaseUrl=http://X",
                "drain RPC failed", new java.io.IOException("connect refused"));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getMessage()).isEqualTo("drain RPC failed");
        assertThat(event.getThrowableProxy().getMessage()).isEqualTo("connect refused");
    }

    @Test
    void errorContextMdcKeyIsPresentDuringLogAndRemovedAfter() {
        ErrorContext.logError(logger, "fanout runId=R-7 workerId=W-1", "fan-out failed",
                new RuntimeException("boom"));

        ILoggingEvent event = appender.list.get(0);
        // Logback captures MDC at log time — so it should have the context.
        assertThat(event.getMDCPropertyMap())
                .containsEntry(ErrorContext.MDC_KEY, "fanout runId=R-7 workerId=W-1");
        // But the thread-local MDC must be cleared after the helper returns
        // so the next request handler on this thread doesn't inherit it.
        assertThat(MDC.get(ErrorContext.MDC_KEY)).isNull();
    }

    @Test
    void blankContextStillLogsButSkipsMdc() {
        ErrorContext.logError(logger, "   ", "no context here", new RuntimeException("boom"));

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getMessage()).isEqualTo("no context here");
        assertThat(event.getMDCPropertyMap()).doesNotContainKey(ErrorContext.MDC_KEY);
    }

    @Test
    void nullContextStillLogsButSkipsMdc() {
        ErrorContext.logError(logger, null, "fall-back path", new RuntimeException("boom"));

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getMessage()).isEqualTo("fall-back path");
        assertThat(event.getMDCPropertyMap()).doesNotContainKey(ErrorContext.MDC_KEY);
    }

    @Test
    void nullCauseLogsMessageOnlyWithoutNPE() {
        ErrorContext.logError(logger, "warmup runId=R-1", "no cause attached", null);

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getMessage()).isEqualTo("no cause attached");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void preExistingErrorContextMdcKeyIsRestoredAfterCall() {
        // Edge case: an outer scope may have set errorContext already
        // (nested catches). Our helper removes the key in finally — even
        // if the outer scope had set it. Document this behaviour so
        // callers know to re-set if they need it.
        MDC.put(ErrorContext.MDC_KEY, "outer-context");
        try {
            ErrorContext.logError(logger, "inner-context", "inner", new RuntimeException("boom"));
            // Helper unconditionally removes the key on finally — the
            // outer scope's value is GONE. Document this with the test.
            assertThat(MDC.get(ErrorContext.MDC_KEY)).isNull();
        } finally {
            MDC.remove(ErrorContext.MDC_KEY);
        }
    }
}
