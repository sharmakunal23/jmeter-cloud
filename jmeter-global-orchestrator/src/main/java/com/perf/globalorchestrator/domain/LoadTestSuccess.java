package com.perf.globalorchestrator.domain;

/** Which terminal run states count as a passing load-test task. */
public enum LoadTestSuccess {
    /** Only COMPLETED passes — FAILED and ABORTED fail the task. */
    COMPLETED_ONLY,
    /** Any terminal state passes; use it when the next step should run regardless. */
    ANY_TERMINAL
}
