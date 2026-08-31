package com.perf.globalorchestrator.domain;

/** How many of an application's health endpoints must answer 2xx for the check to pass. */
public enum HealthRequirement {
    ALL,
    ANY,
    AT_LEAST
}
