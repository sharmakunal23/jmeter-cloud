package com.perf.metricsconsumer.jdbc;

/** {@code ?groupId=} is blank, unknown, or disabled in {@code GROUP_REGISTRY} — a terminal 400 for the producer. */
public class UnknownGroupException extends RuntimeException {

    private final String groupId;

    public UnknownGroupException(String groupId) {
        super(groupId == null || groupId.isBlank()
                ? "groupId is required (?groupId=<application group>)"
                : "unknown or disabled group: " + groupId);
        this.groupId = groupId;
    }

    public String groupId() {
        return groupId;
    }
}
