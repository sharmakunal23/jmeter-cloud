package com.perf.orchestrator.parser;

/**
 * Thrown when a JTL header is missing a column that the orchestrator requires,
 * or when a column lookup is attempted for a name that was not in the header.
 *
 * <p>Always carries a message naming the missing column so the operator
 * can diagnose a misconfigured JMeter JTL save configuration immediately.
 */
public final class ColumnIndexException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ColumnIndexException(String message) {
        super(message);
    }
}
