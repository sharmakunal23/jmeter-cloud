package com.perf.metricsconsumer.model;

/**
 * The size bounds the {@code metrics} schema imposes on wire values. The
 * ingest edge enforces these (400) before a row reaches the database, because
 * a constraint violation there is a 503 that the worker would replay forever.
 */
public final class WireBounds {

    /** {@code runId}, {@code workerId}, {@code region}: {@code VARCHAR2(64 CHAR)}. */
    public static final int ID_CHARS = 64;
    /** {@code label}: {@code VARCHAR2(255 CHAR)} — longer labels are truncated, not rejected. */
    public static final int LABEL_CHARS = 255;
    /** A status code: {@code VARCHAR2(128 CHAR)} — longer codes are truncated, not rejected. */
    public static final int CODE_CHARS = 128;
    /** {@code windowSecond}: {@code NUMBER(10)} — a millisecond value fails this. */
    public static final long MAX_WINDOW_SECOND = 9_999_999_999L;

    private WireBounds() { }
}
