package com.perf.documentservice.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts {@code blobId} from a request path by taking the segment after
 * {@code blob} — covering both {@code /api/v1/blob/{blobId}} and
 * {@code /api/v1/blob/{blobId}/metadata}.
 *
 * <p>Unresolved template segments ({@code {blobId}}) are skipped, so an
 * unmatched route yields no id rather than a literal brace.
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
