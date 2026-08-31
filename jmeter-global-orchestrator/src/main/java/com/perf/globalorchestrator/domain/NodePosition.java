package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Canvas coordinates, round-tripped so a reopened builder looks unchanged. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodePosition(double x, double y) {}
