package com.perf.orchestrator.io;

import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.model.JtlRow;
import com.perf.orchestrator.parser.ColumnIndex;
import com.perf.orchestrator.parser.JtlRowParser;
import com.perf.orchestrator.parser.LineBuffer;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Tails a JMeter JTL file using {@link RandomAccessFile}, assembles complete
 * CSV lines via {@link LineBuffer}, parses them into {@link JtlRow} records,
 * and periodically persists the byte offset for crash recovery.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #tryOpen} — opens the file, reads the header, builds the parser,
 *       restores the crash-recovery offset. Returns empty if the file or header
 *       is not yet ready; the state machine retries on the next poll cycle.</li>
 *   <li>{@link #poll} — reads up to {@code maxReadBytes} per call, feeds bytes
 *       into {@link LineBuffer}, parses complete lines. Returns {@link PollResult}
 *       containing parsed rows and raw bytes read. Called repeatedly in RUNNING
 *       and DRAINING states.</li>
 *   <li>{@link #pollFinal} — called once when DRAINING confirms no more bytes
 *       are incoming. Flushes any partial line from {@link LineBuffer} (the last
 *       line of a JTL file has no trailing newline), forces a final offset
 *       persist, and returns remaining rows.</li>
 *   <li>{@link #close} — closes the underlying {@link RandomAccessFile}.</li>
 * </ol>
 *
 * <h2>Header reading</h2>
 * The header is read byte-by-byte until a newline is found. After {@link #tryOpen}
 * returns, the internal {@link RandomAccessFile} pointer is positioned exactly at
 * the byte after the header's newline — no data bytes are consumed or discarded
 * during header reading.
 *
 * <h2>Crash recovery</h2>
 * On startup, if {@link JtlOffsetStore} holds a saved offset from a previous
 * run, the RAF is seeked to that position and polling resumes without
 * reprocessing earlier rows. If the saved offset exceeds the current file length
 * (stale state from a different run), it is discarded and polling starts just
 * after the header.
 *
 * <h2>Thread safety</h2>
 * Not thread-safe. All methods must be called from the single poll-loop thread.
 */
public final class FilePoller implements Closeable {

    private static final Logger LOG = Logger.getLogger(FilePoller.class.getName());

    private final OrchestratorConfig      config;
    private final JtlRowParser       parser;
    private final JtlOffsetStore  stateStore;
    private final RandomAccessFile   raf;
    private final LineBuffer         lineBuffer;
    private final byte[]             readBuffer;

    private long lastByteOffset;
    private long lastStateFlushMs;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    private FilePoller(RandomAccessFile raf,
                       long initialOffset,
                       JtlRowParser parser,
                       OrchestratorConfig config,
                       JtlOffsetStore stateStore) {
        this.raf            = raf;
        this.lastByteOffset = initialOffset;
        this.parser         = parser;
        this.config         = config;
        this.stateStore     = stateStore;
        this.lineBuffer     = new LineBuffer();
        this.readBuffer     = new byte[config.getMaxReadBytes()];
        this.lastStateFlushMs = System.currentTimeMillis();
    }

    /**
     * Attempts to open the JTL file and prepare it for row polling.
     *
     * <p>Returns {@link Optional#empty()} — without throwing — when:
     * <ul>
     *   <li>The JTL file does not yet exist (JMeter hasn't started writing)</li>
     *   <li>The file exists but the header line has not been fully written
     *       (no {@code \n} found yet)</li>
     * </ul>
     * The state machine treats both cases identically: sleep for
     * {@code fileWaitPollIntervalMs} and retry.
     *
     * <p>Throws {@link IOException} for genuine I/O failures (permission denied,
     * disk error) that the state machine should treat as fatal.
     *
     * @param config     orchestrator configuration supplying the JTL path, buffer size, etc.
     * @param stateStore provides crash-recovery byte offset
     * @return a ready-to-poll {@link FilePoller}, or empty if the file/header
     *         is not yet available
     * @throws IOException on unrecoverable I/O failure
     */
    public static Optional<FilePoller> tryOpen(OrchestratorConfig config,
                                               JtlOffsetStore stateStore)
            throws IOException {

        Objects.requireNonNull(config,     "config cannot be null");
        Objects.requireNonNull(stateStore, "stateStore cannot be null");

        Path jtlPath = Path.of(config.getJtlPath());
        if (!Files.exists(jtlPath)) {
            return Optional.empty();
        }

        RandomAccessFile raf = new RandomAccessFile(jtlPath.toFile(), "r");
        try {
            Optional<String> headerLine = readFirstLine(raf);
            if (headerLine.isEmpty()) {
                raf.close();
                return Optional.empty();
            }

            ColumnIndex  columnIndex = ColumnIndex.parse(headerLine.get());
            JtlRowParser parser      = new JtlRowParser(columnIndex,
                    java.time.ZoneId.of(config.getTimezoneId()));

            long headerEndOffset = raf.getFilePointer(); // exact position after header \n
            long startOffset     = resolveStartOffset(raf, stateStore, headerEndOffset);

            raf.seek(startOffset);

            LOG.info(() -> String.format(
                    "FilePoller opened. Header ends at byte %d. Starting poll at byte %d.",
                    headerEndOffset, startOffset));

            return Optional.of(new FilePoller(raf, startOffset, parser, config, stateStore));

        } catch (IOException ioe) {
            raf.close();
            throw ioe;
        } catch (RuntimeException re) {
            // Preserve the original RuntimeException type so callers can
            // distinguish e.g. ColumnIndexException ("malformed JTL header")
            // from a generic I/O failure. Wrapping it in IOException would
            // collapse both into the same fatal-bucket at the state machine.
            raf.close();
            throw re;
        }
    }

    // -----------------------------------------------------------------------
    // Polling
    // -----------------------------------------------------------------------

    /**
     * Reads up to {@code maxReadBytes} from the JTL file, feeds the bytes into
     * {@link LineBuffer}, parses complete lines, and returns a {@link PollResult}.
     *
     * <p>The byte count in {@link PollResult#bytesRead()} reflects raw bytes
     * read from the file — not the number of rows parsed. Bytes may arrive without
     * producing rows (partial line being assembled, malformed line skipped).
     *
     * <p>Offset is persisted to {@link JtlOffsetStore} approximately every
     * {@code stateFlushIntervalMs} milliseconds.
     *
     * @return poll result; {@link PollResult#noData()} when the file has no new bytes
     * @throws IOException on unrecoverable I/O failure
     */
    public PollResult poll() throws IOException {
        int bytesRead = raf.read(readBuffer);

        if (bytesRead <= 0) {
            persistOffsetIfDue();
            return PollResult.noData();
        }

        lastByteOffset  += bytesRead;

        List<String>  lines = lineBuffer.feed(readBuffer, bytesRead);
        List<JtlRow>  rows  = parseLines(lines);

        persistOffsetIfDue();
        return new PollResult(rows, bytesRead);
    }

    /**
     * Performs a final read and flushes the {@link LineBuffer}, capturing any
     * row whose last bytes were written without a trailing newline.
     *
     * <p>JMeter does not guarantee a trailing newline on the very last row of
     * the JTL file. Without this call, the final row would be silently lost.
     *
     * <p>Always forces a {@link JtlOffsetStore#saveOffset} call, regardless
     * of the periodic flush schedule, to ensure the offset is current before
     * the orchestrator exits.
     *
     * <p>Must be called exactly once, after DRAINING confirms no more bytes
     * are incoming.
     *
     * @return any rows parsed from remaining bytes or the flushed partial line;
     *         may be empty if the file ended cleanly on a newline
     * @throws IOException on unrecoverable I/O failure
     */
    public List<JtlRow> pollFinal() throws IOException {
        // One last read — bytes may have arrived between the last poll and the sentinel
        int bytesRead = raf.read(readBuffer);
        List<String> lines = new ArrayList<>();

        if (bytesRead > 0) {
            lastByteOffset += bytesRead;
            lines.addAll(lineBuffer.feed(readBuffer, bytesRead));
        }

        // Flush any partial line that has no trailing newline
        lineBuffer.flush().ifPresent(lines::add);

        // Unconditional final persist — must happen before DONE transition
        stateStore.saveOffset(lastByteOffset);

        return Collections.unmodifiableList(parseLines(lines));
    }

    // -----------------------------------------------------------------------
    // Closeable
    // -----------------------------------------------------------------------

    @Override
    public void close() throws IOException {
        raf.close();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Reads the file byte-by-byte from position 0 until a {@code \n} is found,
     * stripping any {@code \r} for CRLF compatibility.
     *
     * <p>After this method returns successfully, {@code raf.getFilePointer()}
     * is positioned exactly at the byte immediately following the header newline.
     * No data bytes beyond the header are consumed or discarded.
     *
     * @return the header line without its line terminator, or empty if the file
     *         is empty or the header has not yet been terminated by {@code \n}
     */
    private static Optional<String> readFirstLine(RandomAccessFile raf) throws IOException {
        raf.seek(0);
        StringBuilder sb = new StringBuilder(256);
        int b;
        while ((b = raf.read()) != -1) {
            if (b == '\n') {
                // File pointer is now exactly at the byte after the newline
                return sb.isEmpty() ? Optional.empty() : Optional.of(sb.toString());
            }
            if (b != '\r') {
                sb.append((char) b);
            }
        }
        // Reached EOF without finding a newline — header not complete yet
        return Optional.empty();
    }

    /**
     * Resolves the byte offset at which polling should begin.
     *
     * <ul>
     *   <li>No saved offset (fresh start): begin just after the header</li>
     *   <li>Valid saved offset: resume from there (crash recovery, skips processed rows)</li>
     *   <li>Saved offset exceeds file length (stale state from a prior run): reset to
     *       post-header and log a warning</li>
     * </ul>
     */
    private static long resolveStartOffset(RandomAccessFile raf,
                                           JtlOffsetStore stateStore,
                                           long headerEndOffset) throws IOException {
        long saved = stateStore.loadOffset();
        if (saved <= 0) {
            return headerEndOffset;
        }
        long fileLength = raf.length();
        if (saved > fileLength) {
            LOG.warning(() -> String.format(
                    "Saved offset %d exceeds current file length %d — " +
                    "discarding stale state and resuming from post-header position %d.",
                    saved, fileLength, headerEndOffset));
            return headerEndOffset;
        }
        LOG.info(() -> "Crash recovery: resuming from saved byte offset " + saved);
        return saved;
    }

    private List<JtlRow> parseLines(List<String> lines) {
        List<JtlRow> rows = new ArrayList<>(lines.size());
        for (String line : lines) {
            parser.parse(line).ifPresent(rows::add);
        }
        return rows;
    }

    private void persistOffsetIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastStateFlushMs >= config.getStateFlushIntervalMs()) {
            stateStore.saveOffset(lastByteOffset);
            lastStateFlushMs = now;
        }
    }
}
