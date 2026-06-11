package com.perf.globalorchestrator.observability;

import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * OBSERVABILITY Phase F — operator-readable error context for catch blocks.
 *
 * <p>An exception alone tells the operator <i>what</i> went wrong; the
 * stacktrace tells <i>where</i>. Neither tells them <i>what the system was
 * trying to do at the time</i>. This helper attaches that third piece —
 * a short string like {@code "scaleUp runId=R-1 allocations=3"} — to:
 *
 * <ul>
 *   <li>The JSON log line (as the {@code errorContext} MDC key — already
 *       in {@code logback-spring.xml}'s {@code includeMdcKeyName} list).</li>
 *   <li>The active observation span (as a low-cardinality tag with the
 *       same name) so the trace in Jaeger carries the same context.</li>
 * </ul>
 *
 * <h2>Why a helper</h2>
 * The naive pattern is verbose and easy to get wrong (MDC.put without a
 * matching MDC.remove in finally leaks the key into the next request
 * served on that thread). This helper collapses the pattern into one
 * call, with the lifecycle guaranteed by its own try/finally.
 *
 * <h2>Usage</h2>
 * <pre>
 * try {
 *     // work that might throw
 * } catch (Exception e) {
 *     ErrorContext.logError(LOG,
 *             "scaleUp runId=" + runId + " allocations=" + allocations.size(),
 *             "scale-up fanout failed",
 *             e);
 *     throw e;
 * }
 * </pre>
 *
 * <p>If you only want to log a warning (e.g. a retryable RPC failure
 * that's already handled), call {@link #logWarn} instead.
 */
public final class ErrorContext {

    public static final String MDC_KEY = "errorContext";

    private ErrorContext() {}

    /**
     * Logs at ERROR level with the {@code errorContext} key set on MDC
     * and as a span tag for the duration of the log call.
     *
     * @param log     SLF4J logger from the call-site class
     * @param context operator-readable string. Null / blank means no context
     *                — the log line still fires, just without the tag.
     * @param message human-readable summary line
     * @param cause   throwable to include (may be null)
     */
    public static void logError(Logger log, String context, String message, Throwable cause) {
        log(log, context, message, cause, Level.ERROR);
    }

    /** Same contract as {@link #logError} but at WARN level. */
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
