package com.perf.orchestrator.parser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Accumulates raw bytes from successive {@code RandomAccessFile} reads and
 * emits complete, newline-terminated lines.
 *
 * <h2>Why this exists</h2>
 * {@code RandomAccessFile.read(byte[])} reads however many bytes are available
 * — it has no concept of lines. A single read may deliver:
 * <ul>
 *   <li>Zero bytes (JMeter hasn't flushed yet)</li>
 *   <li>A partial line (JMeter is mid-write)</li>
 *   <li>Multiple complete lines</li>
 *   <li>Several complete lines followed by a partial line</li>
 * </ul>
 * This class absorbs that complexity so {@link JtlRowParser} receives only
 * complete, clean line strings.
 *
 * <h2>CRLF handling</h2>
 * JMeter running on Windows writes {@code \r\n} line endings. The {@code \r}
 * is stripped before a line is emitted so callers never see it.
 *
 * <h2>Empty lines</h2>
 * Blank lines (two consecutive newlines) are silently discarded — JMeter does
 * not produce them, and they would cause {@link JtlRowParser} to emit a
 * warning for every one.
 *
 * <h2>Thread safety</h2>
 * Not thread-safe. Intended for exclusive use by the single poll-loop thread.
 */
public final class LineBuffer {

    /**
     * 4 KB initial capacity — comfortably holds one JTL row including a
     * long failureMessage, without over-allocating for the common case.
     */
    private static final int INITIAL_CAPACITY = 4_096;

    /**
     * Accumulated bytes for the line currently being assembled.
     * Grows automatically; reset to zero after each complete line.
     */
    private byte[] partial;
    private int partialLen;

    public LineBuffer() {
        this.partial    = new byte[INITIAL_CAPACITY];
        this.partialLen = 0;
    }

    // -----------------------------------------------------------------------
    // Core API
    // -----------------------------------------------------------------------

    /**
     * Feeds a chunk of raw bytes into the buffer.
     *
     * <p>Scans {@code data[0..length-1]} for newline characters ({@code \n}).
     * Each newline terminates a line which is emitted (after stripping any
     * trailing {@code \r}) if non-empty. Bytes after the last newline are
     * retained as the start of the next line.
     *
     * @param data   byte array from a {@code RandomAccessFile} read
     * @param length number of valid bytes in {@code data} (may be less than {@code data.length})
     * @return immutable list of complete lines emitted by this feed; empty if no lines completed
     */
    public List<String> feed(byte[] data, int length) {
        if (length <= 0) {
            return Collections.emptyList();
        }

        List<String> lines = new ArrayList<>();

        for (int i = 0; i < length; i++) {
            byte b = data[i];
            if (b == '\n') {
                String line = emitPartial();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            } else {
                appendToPartial(b);
            }
        }

        return lines.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(lines);
    }

    /**
     * Forces the remaining partial content out as a line, without requiring
     * a trailing newline.
     *
     * <p>Called by the state machine when transitioning to DONE — the final
     * line of a JTL file may not have a trailing newline, and we cannot afford
     * to discard it. The buffer is cleared after this call.
     *
     * @return the partial line if any content exists; empty if the buffer is empty
     */
    public Optional<String> flush() {
        if (partialLen == 0) {
            return Optional.empty();
        }
        String line = emitPartial();
        return line.isEmpty() ? Optional.empty() : Optional.of(line);
    }

    /**
     * Returns {@code true} if there are bytes in the partial buffer waiting
     * to be completed. Useful for the DRAINING state to know whether a final
     * {@link #flush()} is needed before shutdown.
     */
    public boolean hasPendingContent() {
        return partialLen > 0;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Converts the current partial buffer to a String (stripping trailing {@code \r})
     * and resets the buffer to empty.
     */
    private String emitPartial() {
        int len = partialLen;

        // Strip trailing \r for Windows CRLF compatibility
        if (len > 0 && partial[len - 1] == '\r') {
            len--;
        }

        String line = new String(partial, 0, len, StandardCharsets.UTF_8);
        partialLen = 0;
        return line;
    }

    /**
     * Appends a single byte to the partial buffer, growing the backing array
     * if necessary. Growth factor of 2× keeps amortised cost O(1).
     */
    private void appendToPartial(byte b) {
        if (partialLen == partial.length) {
            byte[] grown = new byte[partial.length * 2];
            System.arraycopy(partial, 0, grown, 0, partialLen);
            partial = grown;
        }
        partial[partialLen++] = b;
    }
}
