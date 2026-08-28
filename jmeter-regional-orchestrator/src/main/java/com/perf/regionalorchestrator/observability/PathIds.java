package com.perf.regionalorchestrator.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts the pod name from a request URI — the one business id this
 * service's paths carry:
 * <pre>
 *   /api/v1/pods/{podName}/...
 *   /api/v1/workers/{podName}/...
 * </pre>
 *
 * <p>Pure utility, no Spring deps, so unit tests can pin every shape.
 */
public final class PathIds {

    public static final String KEY_POD_NAME = "podName";

    /** Segment label → MDC key. Insertion order preserved for stable iteration. */
    private static final Map<String, String> LABEL_TO_KEY = new LinkedHashMap<>();
    static {
        LABEL_TO_KEY.put("pods",    KEY_POD_NAME);
        LABEL_TO_KEY.put("workers", KEY_POD_NAME);
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
