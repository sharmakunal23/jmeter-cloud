package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One task in a workflow graph, discriminated on the wire by {@code type}.
 *
 * <p>Sealed on purpose: the engine dispatches with an exhaustive {@code switch}
 * over the permitted records, so adding a node type without adding its executor
 * fails to compile rather than at 3 a.m.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = HealthCheckNode.class, name = "HEALTH_CHECK"),
        @JsonSubTypes.Type(value = LoadTestNode.class,    name = "LOAD_TEST"),
        @JsonSubTypes.Type(value = EmailNode.class,       name = "EMAIL"),
        @JsonSubTypes.Type(value = DelayNode.class,       name = "DELAY"),
        @JsonSubTypes.Type(value = ApprovalNode.class,    name = "APPROVAL"),
})
public sealed interface WorkflowNode
        permits HealthCheckNode, LoadTestNode, EmailNode, DelayNode, ApprovalNode {

    String id();

    String name();

    NodePosition position();

    JoinPolicy joinPolicy();

    @JsonIgnore
    NodeType type();

    /** The application this node acts on, or null — the key the execution's metrics split by. */
    @JsonIgnore
    default String applicationName() {
        return null;
    }
}
