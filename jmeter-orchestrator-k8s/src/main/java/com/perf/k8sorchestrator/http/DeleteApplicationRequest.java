package com.perf.k8sorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body for {@code POST /api/v1/applications/{id}/purge}. Optional — an operator
 * may purge with no body at all. {@code reason} is a free-text note recorded on
 * the {@code purgeAudit} tombstone; PII-free by convention.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeleteApplicationRequest(String reason) {}
