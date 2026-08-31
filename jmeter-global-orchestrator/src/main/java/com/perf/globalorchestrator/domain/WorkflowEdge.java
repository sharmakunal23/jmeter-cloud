package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One directed dependency. Two edges out of a node mean its targets run in
 * parallel — that is the only way the graph expresses concurrency.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowEdge(String id, String source, String target, EdgeCondition condition) {

    public WorkflowEdge {
        condition = condition == null ? EdgeCondition.ON_SUCCESS : condition;
    }
}
