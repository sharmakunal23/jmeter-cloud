package com.perf.metricsconsumer.model;

/**
 * The bounds the metrics schema ({@code CARDZATE_DB_GRAF}) imposes on wire
 * values. The ingest edge enforces the identity ones (400) before a row reaches
 * the database, because a constraint violation there is a 503 the worker would
 * replay forever; the label and the counters are bounded by truncation and
 * clamping instead, so one oversized value cannot blank a run.
 */
public final class WireBounds {

    /** {@code runId}, {@code workerId}, {@code region}: the platform's ids fit well inside {@code VARCHAR2(255)} / {@code (64)}. */
    public static final int ID_CHARS = 64;
    /** {@code LABEL_KEY VARCHAR2(1000)} in bytes — longer labels are truncated, not rejected. */
    public static final int LABEL_BYTES = 1000;
    /** {@code WINDOW_SECOND NUMBER(19)} — an epoch second; a millisecond value is the classic producer bug. */
    public static final long MAX_WINDOW_SECOND = 9_999_999_999L;
    /** The {@code NUMBER(10)} counters. */
    public static final long MAX_COUNT = 9_999_999_999L;

    private WireBounds() { }
}
