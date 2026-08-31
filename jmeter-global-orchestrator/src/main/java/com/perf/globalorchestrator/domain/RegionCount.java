package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Workers a load-test node wants in one region — the unit capacity is measured in. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegionCount(String region, int count) {}
