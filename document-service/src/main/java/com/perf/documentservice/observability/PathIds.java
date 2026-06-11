package com.perf.documentservice.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts the {@code blobId} identifier from BlobController paths:
 * <pre>
 *   /api/v1/blob/{blobId}
 *   /api/v1/blob/{blobId}/metadata
 * </pre>
 *
 * <p>Walks path segments and picks the token after each recognised label,
 * mirroring the global-orchestrator's PathIds utility. Pure utility for
 * unit-test isolation.
 */
public final class PathIds {

    public static final String KEY_BLOB_ID = "blobId";

    private static final Map<String, String> LABEL_TO_KEY = new LinkedHashMap<>();
    static {
        LABEL_TO_KEY.put("blob", KEY_BLOB_ID);
    }

    private PathIds() {}

    public static Map<String, String> extract(String uri) {
        if (uri == null || uri.isEmpty()) return Collections.emptyMap();
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        String[] segments = path.split("/");
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < segments.length - 1; i++) {
            String key = LABEL_TO_KEY.get(segments[i]);
            if (key == null) continue;
            String value = segments[i + 1];
            if (value == null || value.isBlank()) continue;
            if (value.startsWith("{") && value.endsWith("}")) continue;
            out.putIfAbsent(key, value);
        }
        return out;
    }
}
