package com.perf.metricsconsumer.observability;

import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * OBSERVABILITY Phase F — operator-readable error context for catch blocks.
 * See the equivalent class in jmeter-global-orchestrator for the full
 * design rationale.
 */
public final class ErrorContext {

    public static final String MDC_KEY = "errorContext";

    private ErrorContext() {}

    public static void logError(Logger log, String context, String message, Throwable cause) {
        log(log, context, message, cause, Level.ERROR);
    }

    public static void logWarn(Logger log, String context, String message, Throwable cause) {
        log(log, context, message, cause, Level.WARN);
    }

    private static void log(Logger log, String context, String message, Throwable cause, Level level) {
        boolean tagged = false;
        try {
            if (context != null && !context.isBlank()) {
                MDC.put(MDC_KEY, context);
                SpanAttributes.tag(MDC_KEY, context);
                tagged = true;
            }
            switch (level) {
                case ERROR:
                    if (cause != null) log.error(message, cause);
                    else log.error(message);
                    break;
                case WARN:
                    if (cause != null) log.warn(message, cause);
                    else log.warn(message);
                    break;
            }
        } finally {
            if (tagged) MDC.remove(MDC_KEY);
        }
    }

    private enum Level { ERROR, WARN }
}
