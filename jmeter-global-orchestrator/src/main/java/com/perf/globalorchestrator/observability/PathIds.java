package com.perf.globalorchestrator.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts well-known identifiers (runId, applicationId, workerId,
 * podName, region) from a request URI.
 *
 * <p>The global-orchestrator's REST paths follow predictable templates:
 * <pre>
 *   /api/v1/runs/{runId}/...
 *   /api/v1/runs/{runId}/members/{workerId}/...
 *   /api/v1/applications/{applicationId}/...
 *   /api/v1/applications/{applicationId}/capacity/{region}/...
 *   /api/v1/applications/{applicationId}/capacity/{region}/pods/{podName}/...
 *   /api/v1/admin/pods/{podName}
 * </pre>
 *
 * <p>Rather than a regex per template (brittle when controllers grow),
 * we walk the path segments and pick the token AFTER each known label
 * — that way the extractor stays correct as new sub-resources are added
 * under the same parent.
 *
 * <p>Pure utility — no Spring deps — so unit tests can pin every shape.
 */
public final class PathIds {

    /** MDC keys correspond 1:1 to the segment labels below. */
    public static final String KEY_RUN_ID         = "runId";
    public static final String KEY_APPLICATION_ID = "applicationId";
    public static final String KEY_WORKER_ID      = "workerId";
    public static final String KEY_POD_NAME       = "podName";
    public static final String KEY_REGION         = "region";

    /** Segment label → MDC key. Insertion order preserved for stable iteration. */
    private static final Map<String, String> LABEL_TO_KEY = new LinkedHashMap<>();
    static {
        LABEL_TO_KEY.put("runs",         KEY_RUN_ID);
        LABEL_TO_KEY.put("applications", KEY_APPLICATION_ID);
        LABEL_TO_KEY.put("members",      KEY_WORKER_ID);
        LABEL_TO_KEY.put("pods",         KEY_POD_NAME);
        LABEL_TO_KEY.put("capacity",     KEY_REGION);
    }

    private PathIds() {}

    /**
     * Returns the MDC-key → value map extracted from the URI. Empty when
     * no recognised labels are present. Values are URI-segment substrings,
     * not URL-decoded — controllers do their own decoding via
     * {@code @PathVariable}.
     */
    public static Map<String, String> extract(String uri) {
        if (uri == null || uri.isEmpty()) return Collections.emptyMap();
        // Strip query string defensively — getRequestURI() never includes
        // it on Servlet 6, but extract() is reused by tests passing raw
        // strings, so a belt-and-braces check is cheap.
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        String[] segments = path.split("/");
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < segments.length - 1; i++) {
            String label = segments[i];
            String key = LABEL_TO_KEY.get(label);
            if (key == null) continue;
            String value = segments[i + 1];
            if (value == null || value.isBlank()) continue;
            // Skip the placeholder template segments (`{runId}`) so unit-tests
            // and accidental template-literal requests don't pollute MDC.
            if (value.startsWith("{") && value.endsWith("}")) continue;
            out.putIfAbsent(key, value);
        }
        return out;
    }
}
