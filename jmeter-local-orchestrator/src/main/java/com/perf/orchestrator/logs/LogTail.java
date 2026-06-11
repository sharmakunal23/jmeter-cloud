package com.perf.orchestrator.logs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded in-memory ring buffer for recent log lines, with a fall-back
 * tail-of-file read for anything older than the buffer holds.
 *
 * <h2>Thread safety</h2>
 * {@link #append(String)} and {@link #tail(int)} are both fully synchronised
 * on the instance. The append rate is bounded by JMeter's stdout +
 * stderr (single-digit lines per second under load), so contention is
 * negligible compared to the pipeline thread.
 *
 * <h2>Memory contract</h2>
 * At most {@code maxLines} String references are retained at any time,
 * with each line bounded by JMeter's own log line size. With the default
 * {@code LOG_BUFFER_LINES=1000} and ~200-byte lines that's well under
 * 1 MB — fits comfortably under the orchestrator's RSS budget.
 *
 * <h2>File fall-back</h2>
 * When the caller asks for more lines than the ring buffer holds, the
 * extras are read tail-first from {@code logFile} (if supplied at
 * construction). The file is opened with {@link RandomAccessFile} and
 * scanned backwards in 8 KB chunks so we don't load multi-GB JMeter logs
 * into RAM.
 */
public final class LogTail implements LogSink {

    private static final Logger LOG = LoggerFactory.getLogger(LogTail.class);

    private static final int FILE_READ_CHUNK_BYTES = 8 * 1024;

    private final int maxLines;
    private final Deque<String> ring;
    /**
     * Path of the JMeter log file the orchestrator is currently tracking.
     * Mutable so {@link com.perf.orchestrator.lifecycle.TestRunManager} can
     * re-point the file-fallback at each run's {@code logs/{runId}/jmeter.log}
     * — see WORKER-HYGIENE Phase A.
     */
    private volatile Path logFile;

    public LogTail(int maxLines) {
        this(maxLines, null);
    }

    public LogTail(int maxLines, Path logFile) {
        if (maxLines <= 0) throw new IllegalArgumentException("maxLines must be > 0");
        this.maxLines = maxLines;
        this.logFile  = logFile;
        this.ring     = new ArrayDeque<>(maxLines);
    }

    /**
     * Re-points the file-fallback at a new log path. Pass {@code null} to
     * disable the file fallback (e.g. between runs once per-run cleanup
     * has deleted the previous log).
     */
    public void setLogFile(Path logFile) {
        this.logFile = logFile;
    }

    @Override
    public synchronized void append(String line) {
        Objects.requireNonNull(line, "line");
        if (ring.size() == maxLines) {
            ring.pollFirst();
        }
        ring.offerLast(line);
    }

    /**
     * Returns up to {@code n} most recent lines, oldest-first. When the
     * ring buffer holds fewer than {@code n}, the remainder is filled
     * by reading the tail of {@link #logFile} (if configured); otherwise
     * the response is just whatever the buffer has.
     */
    public List<String> tail(int n) {
        if (n <= 0) return List.of();

        List<String> fromBuffer;
        synchronized (this) {
            if (ring.size() >= n) {
                List<String> out = new ArrayList<>(n);
                int skip = ring.size() - n;
                int i = 0;
                for (String line : ring) {
                    if (i++ >= skip) out.add(line);
                }
                return out;
            }
            fromBuffer = new ArrayList<>(ring);
        }

        int needed = n - fromBuffer.size();
        if (needed <= 0 || logFile == null || !Files.exists(logFile)) {
            return fromBuffer;
        }

        // Read the file tail, then keep the lines that come BEFORE the
        // earliest ring buffer line. The ring is the source of truth for
        // anything written through the orchestrator drainer; the file
        // fall-back fills in lines that were rotated out.
        List<String> fromFile = readFileTail(needed + fromBuffer.size());
        if (!fromBuffer.isEmpty()) {
            String oldestInRing = fromBuffer.get(0);
            int cutoff = fromFile.lastIndexOf(oldestInRing);
            if (cutoff >= 0) fromFile = fromFile.subList(0, cutoff);
        }

        List<String> result = new ArrayList<>(fromFile.size() + fromBuffer.size());
        result.addAll(fromFile);
        result.addAll(fromBuffer);
        if (result.size() > n) {
            result = result.subList(result.size() - n, result.size());
        }
        return result;
    }

    /** Convenience: returns the tail as a single newline-joined string for direct {@code text/plain} responses. */
    public String tailAsText(int n) {
        return String.join("\n", tail(n));
    }

    /**
     * Last {@code n} lines from the in-memory ring buffer only — never
     * touches the on-disk {@code logFile}. Used by the {@code Console}
     * stream of {@code GET /api/v1/logs?stream=console}, where the caller
     * explicitly wants the merged stdout / stderr the orchestrator's
     * drainer captured (a different surface from JMeter's own
     * {@code jmeter.log}). Oldest-first, matches {@link #tail(int)}'s
     * window semantics.
     */
    public List<String> tailRingOnly(int n) {
        if (n <= 0) return List.of();
        synchronized (this) {
            int size = ring.size();
            if (size == 0) return List.of();
            int skip = Math.max(0, size - n);
            List<String> out = new ArrayList<>(Math.min(size, n));
            int i = 0;
            for (String line : ring) {
                if (i++ >= skip) out.add(line);
            }
            return out;
        }
    }

    /**
     * Last {@code n} lines from {@link #logFile} only — never reads the
     * in-memory ring. Used by {@code GET /api/v1/logs?stream=jmeter} so
     * the operator can tail JMeter's own log4j output, which carries
     * different content from the stdout/stderr the drainer captures.
     * Returns an empty list when no {@code logFile} was configured or
     * the file does not exist yet (e.g. PREPARING state, before the
     * JMeter child has started writing) — empty body is friendlier than
     * a 404 / 500 in a polling UI.
     */
    public List<String> tailFileOnly(int n) {
        if (n <= 0 || logFile == null || !Files.exists(logFile)) return List.of();
        return readFileTail(n);
    }

    /**
     * Reads up to {@code n} lines from the end of {@link #logFile}.
     * Walks backwards in fixed-size chunks so a multi-GB log doesn't
     * blow memory.
     *
     * <p>UTF-8 safety: line boundaries are located by scanning for
     * {@code 0x0A} (the byte for {@code \n}, which never appears as a
     * UTF-8 continuation byte), so chunk boundaries can never split a
     * multi-byte codepoint. The line content is then decoded via
     * {@link String#String(byte[], int, int, java.nio.charset.Charset)
     * String(byte[], offset, len, UTF_8)} on the full byte sequence —
     * never on individual bytes — so non-ASCII characters round-trip
     * correctly.
     */
    private List<String> readFileTail(int n) {
        if (logFile == null) return List.of();
        try (RandomAccessFile f = new RandomAccessFile(logFile.toFile(), "r")) {
            long fileLen = f.length();
            if (fileLen == 0) return List.of();

            byte[] buf = new byte[FILE_READ_CHUNK_BYTES];

            // Bytes belonging to a partial line at the LEFT boundary of the
            // most recently scanned chunk — i.e. bytes whose terminating
            // newline lies somewhere in an earlier chunk we haven't read
            // yet. Carried forward into the next iteration so the line is
            // assembled in its full byte form before UTF-8 decoding.
            byte[] pending = new byte[0];
            ArrayDeque<String> stack = new ArrayDeque<>(n);
            long pos = fileLen;

            outer:
            while (pos > 0) {
                int read = (int) Math.min(buf.length, pos);
                pos -= read;
                f.seek(pos);
                f.readFully(buf, 0, read);

                // `end` is the exclusive upper bound of the unprocessed
                // segment in `buf`. As we walk the chunk backwards and
                // find newlines, the segment between the newline and `end`
                // (plus `pending`) is one full line.
                int end = read;
                for (int i = read - 1; i >= 0; i--) {
                    if (buf[i] == '\n') {
                        int segLen = end - (i + 1);
                        addLine(stack, buf, i + 1, segLen, pending);
                        pending = new byte[0];
                        end = i;
                        if (stack.size() >= n) break outer;
                    }
                }
                // Whatever remains at the front of the chunk (buf[0 .. end))
                // is the start of a line that continues into the previous
                // (older) chunk we'll read next — prepend to `pending`.
                if (end > 0 || pending.length > 0) {
                    byte[] next = new byte[end + pending.length];
                    System.arraycopy(buf,     0, next, 0,         end);
                    System.arraycopy(pending, 0, next, end,       pending.length);
                    pending = next;
                }
            }

            // The very first line in the file has no leading newline — so
            // anything still in `pending` after the loop is exactly that
            // line (assuming we're still under the n-line cap).
            if (stack.size() < n && pending.length > 0) {
                addLine(stack, pending, 0, pending.length, EMPTY_BYTES);
            }

            return new ArrayList<>(stack);
        } catch (IOException io) {
            // Tail-from-file is best-effort — surfacing as an empty tail
            // is friendlier than a 500 from the controller.
            LOG.warn("Could not read tail of {}: {}", logFile, io.toString());
            return List.of();
        }
    }

    private static final byte[] EMPTY_BYTES = new byte[0];

    /**
     * Decodes one assembled line as UTF-8 and pushes it onto the newest-first
     * stack (so iteration later yields oldest-first). A single trailing
     * {@code \r} is stripped to match the original CRLF-tolerance contract.
     * Empty lines are skipped — they were skipped by the previous
     * implementation and {@code GET /logs} is more useful without blanks.
     */
    private static void addLine(Deque<String> stack, byte[] head, int headOffset, int headLen,
                                byte[] tail) {
        int totalLen = headLen + tail.length;
        if (totalLen == 0) return;
        byte[] full = new byte[totalLen];
        System.arraycopy(head, headOffset, full, 0,       headLen);
        System.arraycopy(tail, 0,          full, headLen, tail.length);

        int lineLen = full.length;
        if (lineLen > 0 && full[lineLen - 1] == '\r') lineLen--;
        if (lineLen == 0) return;
        stack.push(new String(full, 0, lineLen, StandardCharsets.UTF_8));
    }

    /**
     * Drops every line currently in the ring buffer. The on-disk
     * {@code logFile} (if configured) is left untouched — the file
     * fall-back path is always last-write-wins from the JMeter child.
     *
     * <p>Intended for test isolation between {@code @WebMvcTest} methods
     * that share the same {@code LogTail} bean; callable from production
     * code (e.g. between runs) but currently unused there because
     * {@link com.perf.orchestrator.lifecycle.TestRunManager} starts each
     * run with a fresh JMeter process whose drainer fills the buffer
     * organically.
     */
    public synchronized void clear() {
        ring.clear();
    }

    /** Test/diagnostic only — returns the in-memory ring size. */
    synchronized int bufferedSize() {
        return ring.size();
    }

    /** Returns the configured {@code maxLines} cap. */
    public int capacity() {
        return maxLines;
    }
}
