package com.perf.globalorchestrator.health;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The platform's health as one tree — the hub is the only component that
 * talks to every other one, so it is the only place the whole picture exists.
 * Served by {@code GET /api/v1/platform/health}; the UI renders it with the
 * nesting intact.
 *
 * <p>Statuses: {@code UP} · {@code DEGRADED} (serving, but a dependency or a
 * child is not) · {@code DOWN} · {@code UNKNOWN} (not probed yet).
 *
 * @param status     the roll-up of the top-level components (DOWN dominates)
 * @param checkedAt  when the snapshot was taken
 * @param components control plane, data plane and data centers, in that order
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlatformHealth(String status, Instant checkedAt, List<Component> components) {

    public static final String UP = "UP";
    public static final String DEGRADED = "DEGRADED";
    public static final String DOWN = "DOWN";
    public static final String UNKNOWN = "UNKNOWN";

    /**
     * One node of the tree.
     *
     * @param id         stable identifier ({@code metrics-consumer}, {@code region.na-east})
     * @param name       what the operator reads ({@code Metrics consumer})
     * @param kind       {@code service} · {@code dependency} · {@code regions} ·
     *                   {@code region} · {@code regional-orchestrator} · {@code workers}
     * @param status     see the class Javadoc
     * @param detail     one line: the reason when not UP, the interesting fact when UP
     * @param url        where the probe went (services and regionals)
     * @param checkedAt  when this component was last probed (regions carry the probe's last verdict)
     * @param latencyMs  the probe's round trip, when it answered
     * @param facts      small typed facts the UI may render (worker counts, versions, free space)
     * @param components children, nested to any depth
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Component(String id, String name, String kind, String status, String detail, String url,
                            Instant checkedAt, Long latencyMs, Map<String, Object> facts, List<Component> components) {

        public static Component leaf(String id, String name, String kind, String status, String detail) {
            return new Component(id, name, kind, status, detail, null, null, null, null, null);
        }
    }

    /** DOWN beats DEGRADED beats UNKNOWN beats UP — the roll-up every parent uses. */
    public static String worst(List<Component> children) {
        String result = UP;
        for (Component c : children) {
            result = worse(result, c.status());
        }
        return result;
    }

    public static String worse(String a, String b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(String s) {
        return switch (s == null ? UNKNOWN : s) {
            case DOWN -> 3;
            case DEGRADED -> 2;
            case UNKNOWN -> 1;
            default -> 0;
        };
    }
}
