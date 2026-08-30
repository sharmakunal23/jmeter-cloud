package com.perf.metricsconsumer.http;

/**
 * The one response shape of {@code POST /api/v1/ingest}, whatever the outcome.
 *
 * @param rowsInserted rows actually inserted — a replay reports 0, and that is success
 * @param code         {@code ACCEPTED}, {@code UNKNOWN_GROUP}, {@code BAD_REQUEST},
 *                     {@code PAYLOAD_TOO_LARGE}, {@code MISSING_PARAMETER}, {@code UNAUTHORIZED},
 *                     {@code UNSUPPORTED_MEDIA_TYPE}, {@code METHOD_NOT_ALLOWED},
 *                     {@code ORACLE_UNAVAILABLE}, {@code INTERNAL_ERROR}
 * @param message      detail for a human; null on success
 */
public record IngestResponse(int rowsInserted, String code, String message) { }
