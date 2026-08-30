package com.perf.orchestrator.lifecycle;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One library plugin for a run: a document-service blob staged under
 * {@code ${PLUGINS_DIR}/<blobId>…} and handed to JMeter via
 * {@code -Jsearch_paths}. A {@code .jar} is a single plugin; a {@code .zip}
 * is a flat bundle of jars (the plugin plus its dependency jars).
 *
 * <p>Both fields are validated here — the {@code StartTestRequest.validateProperties}
 * philosophy — so nothing path- or shell-unsafe can reach the filesystem or
 * the child's command line.
 */
public record PluginSpec(String blobId, String fileName) {

    /** Document-service ids are ULIDs — 26 chars of Crockford base32. */
    private static final Pattern BLOB_ID_PATTERN = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");

    public PluginSpec {
        if (blobId == null || !BLOB_ID_PATTERN.matcher(blobId).matches()) {
            throw new IllegalArgumentException(
                    "plugins[].blobId must be a 26-char ULID ([0-9A-HJKMNP-TV-Z]{26}); got: '" + blobId + "'");
        }
        if (fileName == null || !FILE_NAME_PATTERN.matcher(fileName).matches()
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "plugins[].fileName must match [A-Za-z0-9][A-Za-z0-9._-]{0,254} with no path separators; got: '"
                    + fileName + "'");
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jar") && !lower.endsWith(".zip")) {
            throw new IllegalArgumentException(
                    "plugins[].fileName must end .jar (single plugin) or .zip (bundle of jars); got: '"
                    + fileName + "'");
        }
    }

    /** True when this spec is a zip bundle of jars rather than a single jar. */
    public boolean isBundle() {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".zip");
    }
}
