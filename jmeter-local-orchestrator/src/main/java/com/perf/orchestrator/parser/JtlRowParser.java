package com.perf.orchestrator.parser;

import com.perf.orchestrator.model.JtlRow;
import com.perf.orchestrator.observability.WarningThrottle;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Converts raw JTL CSV lines into {@link JtlRow} records.
 *
 * <h2>Timestamp caching</h2>
 * JMeter writes timestamps at second-level precision ({@code yyyy/MM/dd HH:mm:ss}).
 * At 333 req/s all rows within the same second carry the identical timestamp string.
 * A single-entry cache (last-seen key + value) achieves a ~99.7% hit rate,
 * reducing {@link LocalDateTime#parse} calls to one per second regardless of load.
 * No map, no eviction policy needed — just two fields.
 *
 * <h2>Malformed rows</h2>
 * Any row that cannot be fully parsed is logged at WARNING level and returned
 * as {@link Optional#empty()}. The orchestrator never crashes on bad data — it logs,
 * skips, and continues. Callers should monitor the warning log count as a
 * secondary health signal.
 *
 * <h2>Thread safety</h2>
 * Not thread-safe. The timestamp cache uses unsynchronised fields and is
 * designed for the single poll-loop thread only.
 */
public final class JtlRowParser {

    private static final Logger LOG = Logger.getLogger(JtlRowParser.class.getName());

    /**
     * Matches the timestamp format written by JMeter when configured with
     * {@code jmeter.save.saveservice.timestamp_format=yyyy/MM/dd HH:mm:ss}.
     */
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private final ColumnIndex columnIndex;

    /**
     * Timezone for interpreting JTL timestamps.
     * Defaults to UTC; configurable via {@code TIMEZONE_ID} in {@link com.perf.orchestrator.config.OrchestratorConfig}.
     */
    private final ZoneId zoneId;

    // Single-entry timestamp cache — two fields, zero allocation on hit
    private String cachedTimestampStr;
    private long   cachedEpochSecond;

    /**
     * Per-row WARNING rate-limiters. These sites never fire on a healthy run,
     * but a systematic JTL-format mismatch would otherwise emit one warning per
     * row (~250/s/worker) and churn the capped log-rotation ring. One throttle
     * per category so a flood of one kind doesn't mask another. Per-instance
     * (per-run), single-threaded — matches this parser's threading contract.
     */
    private final WarningThrottle fieldCountWarnings    = new WarningThrottle();
    private final WarningThrottle malformedRowWarnings  = new WarningThrottle();
    private final WarningThrottle booleanColumnWarnings = new WarningThrottle();

    /**
     * Constructs a parser using UTC for timestamp interpretation.
     * Use {@link #JtlRowParser(ColumnIndex, ZoneId)} when the JMeter pod runs
     * in a non-UTC timezone.
     */
    public JtlRowParser(ColumnIndex columnIndex) {
        this(columnIndex, ZoneId.of("UTC"));
    }

    public JtlRowParser(ColumnIndex columnIndex, ZoneId zoneId) {
        this.columnIndex = Objects.requireNonNull(columnIndex, "columnIndex cannot be null");
        this.zoneId      = Objects.requireNonNull(zoneId,      "zoneId cannot be null");
    }

    // -----------------------------------------------------------------------
    // Core API
    // -----------------------------------------------------------------------

    /**
     * Parses one raw CSV line into a {@link JtlRow}.
     *
     * <p>Returns {@link Optional#empty()} and logs a warning for any of:
     * <ul>
     *   <li>Null or blank input</li>
     *   <li>Wrong field count (row doesn't match the header column count)</li>
     *   <li>Unparseable timestamp (catches accidental header line re-reads)</li>
     *   <li>Non-numeric value in a numeric field</li>
     *   <li>Any value that would violate {@link JtlRow}'s own guardrails</li>
     * </ul>
     *
     * @param line a raw CSV line from the JTL file
     * @return the parsed row, or empty if the line could not be parsed
     */
    public Optional<JtlRow> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }

        List<String> fields = CsvSplitter.split(line);

        // Exact field count match — rows with too few fields are incomplete/corrupt,
        // rows with too many indicate a CSV quoting failure in JMeter (log and skip both).
        if (fields.size() != columnIndex.columnCount()) {
            final int got = fields.size();
            fieldCountWarnings.record(
                    () -> LOG.warning(() -> String.format(
                            "Skipping row with %d fields (expected %d). Line: %s",
                            got, columnIndex.columnCount(), abbreviate(line))),
                    suppressed -> LOG.warning(() -> String.format(
                            "Suppressed %d further field-count-mismatch warnings in the last 60s " +
                            "(JTL columns likely drifted from the orchestrator's expected layout).",
                            suppressed)));
            return Optional.empty();
        }

        try {
            return Optional.of(buildRow(fields));
        } catch (DateTimeParseException e) {
            // The most common cause is accidentally re-reading the header line.
            // Logged at FINE to avoid flooding logs if this happens legitimately.
            LOG.fine(() -> "Skipping row with unparseable timestamp. Line: " + abbreviate(line));
            return Optional.empty();
        } catch (Exception e) {
            malformedRowWarnings.record(
                    () -> LOG.warning(() -> String.format(
                            "Skipping malformed row (%s: %s). Line: %s",
                            e.getClass().getSimpleName(), e.getMessage(), abbreviate(line))),
                    suppressed -> LOG.warning(() -> String.format(
                            "Suppressed %d further malformed-row warnings in the last 60s.", suppressed)));
            return Optional.empty();
        }
    }

    // -----------------------------------------------------------------------
    // Row assembly
    // -----------------------------------------------------------------------

    private JtlRow buildRow(List<String> fields) {
        String rawTimestamp = field(fields, "timeStamp");
        long   epochSecond  = toEpochSecond(rawTimestamp);
        long   elapsedMs    = parseLong(fields, "elapsed");
        String label        = field(fields, "label");
        String responseCode = field(fields, "responseCode");
        String responseMsg  = field(fields, "responseMessage");
        String threadName   = field(fields, "threadName");
        String dataType     = field(fields, "dataType");
        boolean success     = parseBoolean(fields, "success");
        String failureMsg   = field(fields, "failureMessage");
        long   bytes        = parseLong(fields, "bytes");
        long   sentBytes    = parseLong(fields, "sentBytes");
        int    grpThreads   = parseInt(fields, "grpThreads");
        int    allThreads   = parseInt(fields, "allThreads");
        String url          = field(fields, "URL");
        long   latencyMs    = parseLong(fields, "Latency");
        long   idleTimeMs   = parseLong(fields, "IdleTime");
        long   connectMs    = parseLong(fields, "Connect");

        return new JtlRow(
                rawTimestamp, epochSecond, elapsedMs, label,
                responseCode, responseMsg, threadName, dataType, success,
                failureMsg, bytes, sentBytes, grpThreads, allThreads,
                url, latencyMs, idleTimeMs, connectMs
        );
    }

    // -----------------------------------------------------------------------
    // Timestamp parsing with single-entry cache
    // -----------------------------------------------------------------------

    /**
     * Converts a raw timestamp string to Unix epoch seconds, using a single-entry
     * cache to avoid redundant parsing within the same second.
     *
     * <p>At 333 req/s this reduces parse calls from 333/s to 1/s — a 99.7%
     * reduction with zero GC overhead (no Map, no wrapper objects).
     *
     * <p>Timestamps are interpreted as UTC. JMeter records wall-clock time
     * and the orchestrator runs in the same timezone as JMeter, so UTC interpretation
     * is consistent — all that matters is that the same convention is used across
     * all 30 pods.
     */
    private long toEpochSecond(String ts) {
        if (ts.equals(cachedTimestampStr)) {
            return cachedEpochSecond;
        }
        cachedEpochSecond  = LocalDateTime.parse(ts, TIMESTAMP_FMT)
                .atZone(zoneId)
                .toEpochSecond();
        cachedTimestampStr = ts;
        return cachedEpochSecond;
    }

    // -----------------------------------------------------------------------
    // Field extraction helpers
    // -----------------------------------------------------------------------

    private String field(List<String> fields, String columnName) {
        return fields.get(columnIndex.indexOf(columnName));
    }

    private long parseLong(List<String> fields, String columnName) {
        String raw = field(fields, columnName);
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Column '" + columnName + "' expected a long, got: '" + raw + "'");
        }
    }

    private int parseInt(List<String> fields, String columnName) {
        String raw = field(fields, columnName);
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Column '" + columnName + "' expected an int, got: '" + raw + "'");
        }
    }

    /**
     * JMeter writes {@code "true"} or {@code "false"} (lowercase) for the success column.
     * Any other value is treated as {@code false} (request failed) and logged at WARNING
     * so operators can detect data format issues without crashing the orchestrator.
     */
    private boolean parseBoolean(List<String> fields, String columnName) {
        String raw = field(fields, columnName).trim();
        if (raw.equals("true"))  return true;
        if (raw.equals("false")) return false;
        booleanColumnWarnings.record(
                () -> LOG.warning(() -> String.format(
                        "Unexpected value for boolean column '%s': '%s'. " +
                        "Expected 'true' or 'false'. Treating as false.",
                        columnName, raw)),
                suppressed -> LOG.warning(() -> String.format(
                        "Suppressed %d further unexpected-boolean-value warnings in the last 60s.",
                        suppressed)));
        return false;
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    /** Truncates long lines for log messages to avoid flooding logs with huge response bodies. */
    private static String abbreviate(String line) {
        return line.length() <= 120 ? line : line.substring(0, 120) + "…";
    }
}
