package com.perf.orchestrator.lifecycle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Body of {@code POST /api/v1/test} — matches the {@code StartTestRequest}
 * schema in {@code api/openapi.yaml}.
 *
 * <p>Only {@code runId} is required. Every other field is an override of
 * the env-var default per the documented hierarchy:
 * <em>request body &gt; env var &gt; built-in default</em>.
 *
 * <p>Unknown fields are ignored — the wire schema may grow ahead of this
 * record (e.g. when the Document Service contract lands in step 9), and
 * a strict deserialiser would reject perfectly valid clients.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StartTestRequest(
        String runId,
        String region,
        String scheduledStartAt,
        /**
         * Bug-fix 2026-05-10 — document-service blobId of the test plan to
         * fetch + stage before this run starts. When non-null AND the
         * orchestrator is configured with {@code ARTIFACT_SOURCE=DOCUMENT_SERVICE},
         * the orchestrator pulls the blob and atomically replaces the
         * staged {@code plan.jmx} so the run executes the operator's
         * intended plan instead of whatever was staged by a prior run.
         *
         * <p>When null, the orchestrator runs whatever's already staged
         * (the legacy {@code HTTP_UPLOAD} flow where pods are staged
         * out-of-band via {@code POST /api/v1/testPlan}).
         */
        String testPlanBlobId,
        /** Same shape as {@link #testPlanBlobId} but for the data-files zip. */
        String dataFilesBlobId,
        List<String> jmeterArgs,
        List<String> jmeterJvmArgs,
        /**
         * Track G (Step 31) — JMeter system properties forwarded as
         * {@code -J<key>=<value>} args to the child process. Validated
         * at request time so a malformed key can't escape into the
         * shell command line.
         */
        Map<String, String> properties,
        String kafkaBrokers,
        String schemaRegistryUrl,
        String kafkaTopic,
        String workerIdSource,
        String resultSink,
        Boolean autoUploadResults,
        /**
         * MID-TEST-SCALING Phase C — seconds since {@code run.startedAt} at
         * which this worker joined the run. {@code 0} (or null) for
         * original-fleet workers; {@code > 0} for mid-test scale-up
         * joiners (set by the global-orchestrator's
         * {@code POST /api/v1/runs/{runId}/scaleUp} fan-out).
         *
         * <p>Forwarded onto every published {@link com.perf.orchestrator.WorkerMetricBatch}
         * so the consumer + UI can compute per-second fleet rollups
         * (sum over members live at second X).
         */
        Long joinedAtSecond,
        /**
         * The run's application name (set by the global-orchestrator from the
         * run record). Used only when {@code AUTO_UPLOAD_RESULTS=true} +
         * {@code RESULT_SINK=DOCUMENT_SERVICE} — forwarded to the Document
         * Service as {@code X-Application} so saved results file under the
         * right app for the download-all-by-run flow. Null for untagged runs.
         */
        String application,
        /**
         * Per-run override of the aggregator's late-arrival grace period
         * (seconds). A 1-second window stays open until the tailer has seen
         * this many seconds of newer data before closing, so slow samples
         * (JMeter timestamps a sample at its <em>start</em> but writes it at
         * <em>completion</em>) land in their correct window instead of being
         * dropped as "late". Trades a proportional reporting lag + a little
         * aggregator memory for completeness — size it to your worst tolerable
         * response time. {@code null} falls back to the orchestrator's
         * {@code GRACE_PERIOD_SECONDS} env (default 2). Validated downstream by
         * {@code OrchestratorConfig.from} (must be a positive integer).
         */
        Integer gracePeriodSeconds) {

    private static final Pattern KEY_PATTERN  = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]{0,63}");
    private static final int MAX_VALUE_LENGTH = 256;

    public StartTestRequest {
        jmeterArgs    = jmeterArgs    == null ? List.of() : List.copyOf(jmeterArgs);
        jmeterJvmArgs = jmeterJvmArgs == null ? List.of() : List.copyOf(jmeterJvmArgs);
        properties    = properties    == null ? Map.of()
                : Map.copyOf(validateProperties(properties));
    }

    /**
     * Enforces the {@code properties} contract:
     * <ul>
     *   <li>Keys match {@code [A-Za-z_][A-Za-z0-9_.]{0,63}} — no shell
     *       metacharacters, no path separators, no leading digit.</li>
     *   <li>Values ≤ 256 chars and contain no control characters.</li>
     * </ul>
     * Returns a defensively-ordered copy (LinkedHashMap) so the
     * resulting command line is reproducible.
     */
    private static Map<String, String> validateProperties(Map<String, String> raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>(raw.size());
        raw.forEach((k, v) -> {
            if (k == null || !KEY_PATTERN.matcher(k).matches()) {
                throw new IllegalArgumentException(
                        "properties key '" + k + "' is invalid — must match "
                        + "[A-Za-z_][A-Za-z0-9_.]{0,63}");
            }
            if (v == null) {
                throw new IllegalArgumentException(
                        "properties value for key '" + k + "' is null");
            }
            if (v.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "properties value for '" + k + "' exceeds "
                        + MAX_VALUE_LENGTH + " chars");
            }
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c < 0x20 || c == 0x7F) {
                    throw new IllegalArgumentException(
                            "properties value for '" + k + "' contains a control character");
                }
            }
            out.put(k, v);
        });
        return out;
    }

    /**
     * Parses {@link #scheduledStartAt} as an ISO-8601 instant.
     *
     * <p>Returns empty when no schedule was supplied. Throws
     * {@link IllegalArgumentException} (caught by {@code TestRunManager.start})
     * when the string is present but not a valid timestamp — handled as
     * {@code 400 BAD_REQUEST} by the controller.
     */
    public Optional<Instant> scheduledStartInstant() {
        if (scheduledStartAt == null || scheduledStartAt.isBlank()) return Optional.empty();
        try {
            return Optional.of(Instant.parse(scheduledStartAt));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "scheduledStartAt must be ISO-8601 (e.g. 2026-05-03T15:00:00Z); got: " + scheduledStartAt);
        }
    }
}
