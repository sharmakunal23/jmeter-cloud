package com.perf.k8sorchestrator.domain;

import java.util.List;
import java.util.Map;

/**
 * HM-5 — batched per-second timeseries for the side-by-side comparison
 * view. Returned by {@code GET /api/v1/runs/timeseries?ids=A,B}; the UI
 * compares exactly two runs (decision logged 2026-05-10), which is why
 * this is a partial-200 shape rather than a strict all-or-nothing
 * response: if one of the two runs has been purged or never existed,
 * the operator should still see the other run's chart with the missing
 * one called out.
 *
 * <p>{@link #runs()} is keyed by {@code runId} so caller code looks up
 * by id rather than relying on the order of the {@code ids} query
 * param. {@link #missing()} carries the ids that did not resolve to a
 * known run.
 */
public record MetricsTimeseriesBatch(
        Map<String, MetricsTimeseries> runs,
        List<String> missing
) { }
