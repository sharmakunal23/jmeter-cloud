package com.perf.globalorchestrator.domain;

/** How a node with several inbound edges decides it may start. */
public enum JoinPolicy {
    /** Every inbound edge must be satisfied — the default. */
    ALL,
    /** One satisfied inbound edge is enough; the rest are abandoned. */
    ANY
}
