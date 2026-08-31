package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Sends one HTML mail. Empty {@code to} inherits the group's {@code notifyTo};
 * {@code cc} and {@code bcc} inherit theirs the same way, so a group that
 * changes owners does not need every workflow edited.
 *
 * <p>{@code subject} and {@code body} accept {@code ${…}} placeholders resolved
 * against the execution — see {@code EmailTemplateRenderer} for the vocabulary.
 * A placeholder that names nothing renders empty rather than failing the send.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmailNode(
        String id,
        String name,
        NodePosition position,
        JoinPolicy joinPolicy,
        List<String> to,
        List<String> cc,
        List<String> bcc,
        String subject,
        String body,
        /** Append a table of every task's state, timing and result to the body. */
        boolean includeSummary) implements WorkflowNode {

    public EmailNode {
        joinPolicy = joinPolicy == null ? JoinPolicy.ALL : joinPolicy;
        to  = to  == null ? List.of() : List.copyOf(to);
        cc  = cc  == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
    }

    @Override public NodeType type() { return NodeType.EMAIL; }
}
