package com.perf.k8sorchestrator.observability;

import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Attaches an operator-readable label to a catch block — something like
 * {@code "scaleUp runId=R-1 allocations=3"} — as the {@code errorContext} MDC
 * key on the JSON log line.
 *
 * <p>An exception says what failed and the stacktrace says where; neither says
 * what the system was trying to do. This helper supplies that, and owns the
 * MDC lifecycle in its own try/finally — the hand-rolled version leaks the key
 * into the next request served on that thread whenever the {@code remove} is
 * missed.
 */
public final class ErrorContext {

    public static final String MDC_KEY = "errorContext";

    private ErrorContext() {}

    /**
     * Logs at ERROR level with the {@code errorContext} key set on MDC
     * for the duration of the log call.
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
