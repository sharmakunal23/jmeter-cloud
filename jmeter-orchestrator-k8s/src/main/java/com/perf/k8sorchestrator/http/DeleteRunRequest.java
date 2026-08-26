package com.perf.k8sorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body for {@code DELETE /api/v1/runs/{runId}}. Optional — an operator may hide
 * a run with no body at all. {@code reason} is a free-text note recorded on the
 * DELETE audit event; PII-free by convention (don't put secrets here — the
 * audit timeline is shared).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeleteRunRequest(String reason) {}
