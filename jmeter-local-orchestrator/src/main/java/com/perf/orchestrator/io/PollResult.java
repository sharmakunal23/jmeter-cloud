package com.perf.orchestrator.io;

import com.perf.orchestrator.model.JtlRow;

import java.util.List;

/**
 * Immutable result of a single {@link FilePoller#poll()} call.
 *
 * <p>Carries two pieces of information the state machine needs separately:
 * <ul>
 *   <li>{@code rows} — successfully parsed {@link JtlRow} records for the aggregator</li>
 *   <li>{@code bytesRead} — raw bytes read from the JTL file this poll cycle</li>
 * </ul>
 *
 * <p>The {@code bytesRead} count is intentionally separate from {@code rows.size()}
 * because the two can diverge:
 * <ul>
 *   <li>Bytes arrive but no newline yet → {@code bytesRead > 0}, {@code rows} empty
 *       (the {@link com.perf.orchestrator.parser.LineBuffer} is assembling the line)</li>
 *   <li>A complete line arrives but is malformed → {@code bytesRead > 0}, {@code rows} empty
 *       (the parser skipped it)</li>
 * </ul>
 *
 * <p>The DRAINING state uses {@link #hadNewData()} — not {@code rows.isEmpty()} — to
 * decide whether the file still has bytes to drain. A run with 100% parse failures
 * would incorrectly look like an idle file if we relied on row count alone.
 */
public record PollResult(
        List<JtlRow> rows,
        int bytesRead
) {
    /** Enforces immutability regardless of what the caller passes in. */
    public PollResult {
        rows = List.copyOf(rows);
    }

    /** Canonical empty result returned when the file has no new bytes. */
    private static final PollResult NO_DATA = new PollResult(List.of(), 0);

    public static PollResult noData() {
        return NO_DATA;
    }

    /**
     * Returns {@code true} if new bytes were read from the JTL file this cycle,
     * regardless of whether any rows were successfully parsed from them.
     */
    public boolean hadNewData() {
        return bytesRead > 0;
    }
}
