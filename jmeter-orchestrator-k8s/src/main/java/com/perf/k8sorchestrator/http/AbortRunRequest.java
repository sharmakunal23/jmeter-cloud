package com.perf.k8sorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body for {@code POST /api/v1/runs/{runId}/abort}. Optional — an operator may
 * abort with no body at all. {@code reason} is a free-text note recorded on the
 * ABORT audit event and the run's {@code stateReason}; PII-free by convention
 * (don't put secrets here — the audit timeline is shared).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AbortRunRequest(String reason) {}
