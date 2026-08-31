package com.perf.globalorchestrator.domain;

/** What a workflow node does; the engine dispatches its executor on this. */
public enum NodeType {
    HEALTH_CHECK,
    LOAD_TEST,
    EMAIL,
    DELAY,
    APPROVAL
}
