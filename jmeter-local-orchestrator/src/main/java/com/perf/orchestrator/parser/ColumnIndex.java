package com.perf.orchestrator.parser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps JTL column names to their zero-based positions in a CSV row.
 *
 * <p>Built once from the header line at the top of the JTL file. All subsequent
 * row parsing uses this index to extract fields by name rather than by hardcoded
 * position — making the parser resilient to JMeter column order variations and
 * future JMeter version changes.
 *
 * <p>Validates at construction time that every column needed to populate a
 * {@link com.perf.orchestrator.model.JtlRow} is present. Failing here is far
 * preferable to discovering a missing column after millions of rows have been
 * silently dropped.
 *
 * <p>Immutable once constructed.
 */
public final class ColumnIndex {

    /**
     * The exact column names JMeter writes in the JTL header when using the
     * {@code yyyy/MM/dd HH:mm:ss} timestamp format with default save settings.
     *
     * <p>Note the mixed capitalisation: JMeter uses {@code URL} (all caps),
     * {@code Latency} (title case), etc. Column lookup is case-sensitive.
     */
    static final Set<String> REQUIRED_COLUMNS = Set.of(
            "timeStamp", "elapsed",   "label",       "responseCode",
            "responseMessage",        "threadName",  "dataType",    "success",
            "failureMessage",         "bytes",       "sentBytes",   "grpThreads",
            "allThreads",  "URL",     "Latency",     "IdleTime",    "Connect"
    );

    /** Preserves insertion order so {@code toString()} is stable for logging. */
    private final Map<String, Integer> index;

    /** Retained for error messages that name the offending header. */
    private final String headerLine;

    private ColumnIndex(Map<String, Integer> index, String headerLine) {
        this.index      = Collections.unmodifiableMap(index);
        this.headerLine = headerLine;
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    /**
     * Parses a raw JTL header line and constructs a validated column index.
     *
     * @param headerLine the first line of the JTL file
     * @return a validated, immutable column index
     * @throws ColumnIndexException if any required column is absent from the header
     * @throws NullPointerException if {@code headerLine} is null
     */
    public static ColumnIndex parse(String headerLine) {
        Objects.requireNonNull(headerLine, "headerLine cannot be null");

        List<String> columns = CsvSplitter.split(headerLine);

        // Trim whitespace — some JMeter configs produce " timeStamp" with a leading space
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            String name = columns.get(i).trim();
            if (!name.isEmpty()) {
                idx.put(name, i);
            }
        }

        validateRequiredColumnsPresent(idx, headerLine);
        return new ColumnIndex(idx, headerLine);
    }

    // -----------------------------------------------------------------------
    // Column lookup
    // -----------------------------------------------------------------------

    /**
     * Returns the zero-based column index for the given column name.
     *
     * @param columnName the JMeter column name, case-sensitive
     * @return zero-based position in a CSV row
     * @throws ColumnIndexException if the column was not in the parsed header
     */
    public int indexOf(String columnName) {
        Integer position = index.get(columnName);
        if (position == null) {
            throw new ColumnIndexException(
                    "Column '" + columnName + "' not found in JTL header. " +
                    "Header was: " + headerLine);
        }
        return position;
    }

    /**
     * Returns the total number of columns found in the header.
     * Used by {@link JtlRowParser} to detect rows with the wrong field count.
     */
    public int columnCount() {
        return index.size();
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    private static void validateRequiredColumnsPresent(Map<String, Integer> idx, String headerLine) {
        Set<String> missing = REQUIRED_COLUMNS.stream()
                .filter(col -> !idx.containsKey(col))
                .collect(Collectors.toSet());

        if (!missing.isEmpty()) {
            String cols = missing.stream().sorted().collect(Collectors.joining(", "));
            throw new ColumnIndexException(
                    "JTL header is missing required columns: " + cols + ". " +
                    "Ensure JMeter is configured to save all default fields. " +
                    "Header received: " + headerLine);
        }
    }

    @Override
    public String toString() {
        return "ColumnIndex{columns=" + index.keySet() + "}";
    }
}
