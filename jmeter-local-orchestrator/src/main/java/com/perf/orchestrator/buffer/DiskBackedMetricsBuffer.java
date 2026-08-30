package com.perf.orchestrator.buffer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.orchestrator.model.WorkerMetricBatch;
import com.perf.orchestrator.observability.WarningThrottle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Disk-backed {@link MetricsBuffer}: one gzipped JSON file per envelope under
 * {@code <bufferDir>/<id>.envelope.gz}, holding the exact payload the wire
 * carries, with an in-memory {@link ConcurrentSkipListMap} index giving
 * O(log n) {@link #peekOldest} and O(1) {@link #delete}.
 *
 * <p><b>The atomic rename is the durability boundary.</b> Every write is
 * serialize → gzip → {@code .tmp} → {@code Files.move(ATOMIC_MOVE)}, so
 * {@link #peekOldest} and the boot scrubber only ever see a complete file and a
 * crash mid-write leaves an orphaned {@code .tmp} for the scrubber.
 *
 * <p><b>{@code minFreeDiskBytes} is the load-bearing knob:</b> below it, writes
 * are refused whatever the buffer cap says, so JMeter always wins the
 * disk-pressure contest. Enqueue then applies, in order — refuse on low disk,
 * refuse if gzipped size exceeds {@code maxFileBytes}, sweep envelopes past
 * {@code maxAge}, drop oldest until the new one fits, persist.
 *
 * <p>Every one of those paths discards data, and its throttled WARN is the only
 * signal it happened. A single shared {@link WarningThrottle} bounds the volume;
 * all drop paths run on the one dispatch thread, matching its contract.
 */
public final class DiskBackedMetricsBuffer implements MetricsBuffer {

    private static final Logger LOG = Logger.getLogger(DiskBackedMetricsBuffer.class.getName());

    private static final String ENVELOPE_SUFFIX = ".envelope.gz";
    /**
     * Separates the id from the group in a filename ({@code <id>~<groupId>.envelope.gz});
     * the group's charset ({@code [a-z][a-z0-9_]{0,29}}) never contains it, and
     * ids sort unchanged because they are fixed-width and come first.
     */
    private static final char GROUP_SEPARATOR = '~';
    private static final java.util.regex.Pattern GROUP_ID = java.util.regex.Pattern.compile("[a-z][a-z0-9_]{0,29}");
    private static final String TMP_SUFFIX      = ".envelope.gz.tmp";
    /** Sidecars from an older buffer format. No longer written; the boot scrub
     *  and delete paths still remove any leftovers. */
    private static final String META_SUFFIX     = ".meta";
    private static final String META_TMP_SUFFIX = ".meta.tmp";

    /** Jackson mapper is thread-safe — share a singleton. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path bufferDir;
    private final DiskBackedMetricsBufferConfig cfg;
    private final Clock clock;
    private final AtomicLong idCounter = new AtomicLong();

    /**
     * Index of currently-buffered envelopes. ULID-style keys give chronological
     * order via lexicographic comparison; the concurrent skip-list lets
     * {@link #depthBytes} / {@link #depthEnvelopes} readers run concurrently
     * with dispatch-thread enqueue/delete.
     */
    private final ConcurrentSkipListMap<String, BufferedEnvelope> index = new ConcurrentSkipListMap<>();

    /** Cached running sum of file sizes — depth reads avoid walking the index. */
    private final AtomicLong totalBytes = new AtomicLong();

    /** SLIMDOWN D-4 — sole signal for every drop class; see class javadoc. */
    private final WarningThrottle dropWarnings = new WarningThrottle();

    public DiskBackedMetricsBuffer(Path bufferDir,
                                   DiskBackedMetricsBufferConfig cfg,
                                   Clock clock) {
        this.bufferDir = Objects.requireNonNull(bufferDir, "bufferDir cannot be null");
        this.cfg = Objects.requireNonNull(cfg, "cfg cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");

        try {
            Files.createDirectories(bufferDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create buffer dir " + bufferDir, e);
        }
        bootScrub();
    }

    /** Throttled drop WARN — one line per dropped envelope up to the burst,
     *  then a suppressed-count summary per window. */
    private void warnDrop(String reason, String detail) {
        dropWarnings.record(
                () -> LOG.warning(() -> String.format(
                        "Metrics buffer DROP (%s): %s — envelope lost", reason, detail)),
                suppressed -> LOG.warning(() -> String.format(
                        "Metrics buffer: %d further drops suppressed in the last minute", suppressed)));
    }

    // -----------------------------------------------------------------------
    // Boot scrubber
    // -----------------------------------------------------------------------

    private void bootScrub() {
        long recovered = 0;
        long orphans = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(bufferDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.endsWith(TMP_SUFFIX) || name.endsWith(META_TMP_SUFFIX)
                        || name.endsWith(META_SUFFIX)) {
                    // .tmp = partial write from a prior crash; .meta[.tmp] =
                    // an older buffer format. Both are dead weight — remove.
                    try {
                        Files.deleteIfExists(entry);
                        orphans++;
                    } catch (IOException e) {
                        LOG.log(Level.WARNING, "bootScrub: could not delete orphan " + entry, e);
                    }
                } else if (name.endsWith(ENVELOPE_SUFFIX)) {
                    String stem = name.substring(0, name.length() - ENVELOPE_SUFFIX.length());
                    int sep = stem.indexOf(GROUP_SEPARATOR);
                    String id = sep < 0 ? stem : stem.substring(0, sep);
                    String groupId = sep < 0 ? null : stem.substring(sep + 1);
                    try {
                        long size = Files.size(entry);
                        Instant when = Files.getLastModifiedTime(entry).toInstant();
                        WorkerMetricBatch env = readEnvelope(entry);
                        BufferedEnvelope handle = new BufferedEnvelope(id, entry, size, when, env, groupId);
                        index.put(id, handle);
                        totalBytes.addAndGet(size);
                        recovered++;
                    } catch (Exception e) {
                        // Corrupt, or written by an older encoding. Either way
                        // it is dead weight — drop it and keep boot resilient.
                        LOG.log(Level.WARNING, "bootScrub: could not load " + entry + " — deleting", e);
                        try {
                            Files.deleteIfExists(entry);
                        } catch (IOException ignored) { /* nothing to do */ }
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "bootScrub: directory walk failed for " + bufferDir, e);
        }
        if (recovered > 0) {
            LOG.info(() -> String.format(
                    "bootScrub: recovered %d envelopes (%d bytes) from %s",
                    index.size(), totalBytes.get(), bufferDir));
        }
        if (orphans > 0) {
            final long orphanCount = orphans;
            LOG.warning(() -> String.format(
                    "bootScrub: removed %d orphan .tmp files from %s — likely a prior crash mid-write",
                    orphanCount, bufferDir));
        }
    }

    // -----------------------------------------------------------------------
    // Enqueue
    // -----------------------------------------------------------------------

    @Override
    public synchronized Optional<BufferedEnvelope> enqueue(WorkerMetricBatch envelope, String groupId) {
        Objects.requireNonNull(envelope, "envelope must be non-null");
        final String group;
        if (groupId != null && !GROUP_ID.matcher(groupId).matches()) {
            // Validated upstream (StartTestRequest / METRICS_GROUP_ID); never let
            // an odd value reach a filename.
            LOG.warning("Metrics buffer: ignoring invalid groupId '" + groupId + "' — posting without ?groupId=");
            group = null;
        } else {
            group = groupId;
        }

        // Step 1 — Free-disk reservation (JMeter wins)
        long freeDisk = freeDiskBytesSafe();
        if (freeDisk < cfg.minFreeDiskBytes()) {
            warnDrop("lowDisk", "free disk " + freeDisk + " B below reserve "
                    + cfg.minFreeDiskBytes() + " B (JMeter wins)");
            return Optional.empty();
        }

        // Serialize + gzip first so we know the on-disk size before we touch the cap math.
        byte[] payload;
        try {
            payload = serialize(envelope);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize envelope", e);
        }

        // Step 2 — Per-file size cap
        if (payload.length > cfg.maxFileBytes()) {
            warnDrop("oversize", "gzipped envelope " + payload.length
                    + " B exceeds maxFileBytes " + cfg.maxFileBytes() + " B");
            return Optional.empty();
        }

        // Step 3 — TTL sweep (cheap O(buffered count) walk; usually a no-op)
        ttlSweep();

        // Step 4 — Drop-oldest until there is room for the new envelope
        evictOldestUntilHeadroom(payload.length);

        // Step 5 — Persist. The atomic rename is the durability boundary —
        // peekOldest and the boot scrubber only ever see the final filename.
        String id = nextId();
        String stem     = group == null ? id : id + GROUP_SEPARATOR + group;
        Path tmp        = bufferDir.resolve(stem + TMP_SUFFIX);
        Path target     = bufferDir.resolve(stem + ENVELOPE_SUFFIX);
        try {
            Files.write(tmp, payload);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* nothing more */ }
            throw new UncheckedIOException("Failed to persist envelope to " + target, e);
        }

        BufferedEnvelope handle = new BufferedEnvelope(
                id, target, payload.length, clock.instant(), envelope, group);
        index.put(id, handle);
        totalBytes.addAndGet(payload.length);
        return Optional.of(handle);
    }

    private void ttlSweep() {
        Instant cutoff = clock.instant().minus(cfg.maxAge());
        Iterator<Map.Entry<String, BufferedEnvelope>> it = index.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, BufferedEnvelope> e = it.next();
            BufferedEnvelope env = e.getValue();
            if (env.enqueuedAt().isBefore(cutoff)) {
                it.remove();
                deleteFileQuietly(env.file());
                deleteFileQuietly(bufferDir.resolve(env.id() + META_SUFFIX));
                totalBytes.addAndGet(-env.sizeBytes());
                warnDrop("age", "envelope " + env.id() + " exceeded TTL " + cfg.maxAge());
            } else {
                // Index is in chronological order (ULID prefix is millis); first
                // non-stale entry means everything after is fresher → stop.
                break;
            }
        }
    }

    private void evictOldestUntilHeadroom(long incomingBytes) {
        long cap = cfg.maxBytes();
        while (totalBytes.get() + incomingBytes > cap && !index.isEmpty()) {
            Map.Entry<String, BufferedEnvelope> oldest = index.pollFirstEntry();
            if (oldest == null) break;
            BufferedEnvelope env = oldest.getValue();
            deleteFileQuietly(env.file());
            deleteFileQuietly(bufferDir.resolve(env.id() + META_SUFFIX));
            totalBytes.addAndGet(-env.sizeBytes());
            warnDrop("cap", "evicted oldest envelope " + env.id()
                    + " — buffer at byte cap " + cap + " B");
        }
    }

    // -----------------------------------------------------------------------
    // Peek + delete
    // -----------------------------------------------------------------------

    @Override
    public Optional<BufferedEnvelope> peekOldest() {
        Map.Entry<String, BufferedEnvelope> first = index.firstEntry();
        return first == null ? Optional.empty() : Optional.of(first.getValue());
    }

    @Override
    public void delete(BufferedEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must be non-null");
        BufferedEnvelope removed = index.remove(envelope.id());
        if (removed == null) {
            return; // already deleted; idempotent
        }
        deleteFileQuietly(removed.file());
        deleteFileQuietly(bufferDir.resolve(removed.id() + META_SUFFIX));
        totalBytes.addAndGet(-removed.sizeBytes());
    }

    // -----------------------------------------------------------------------
    // Sizes
    // -----------------------------------------------------------------------

    @Override
    public long depthBytes() {
        return totalBytes.get();
    }

    @Override
    public long depthEnvelopes() {
        return index.size();
    }

    // -----------------------------------------------------------------------
    // Closeable
    // -----------------------------------------------------------------------

    @Override
    public void close() {
        // Buffer state on disk is the persistent part; no in-memory resources
        // beyond the index need releasing. The boot scrubber will rebuild the
        // index on next start.
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns a sortable, monotonically-increasing identifier. */
    String nextId() {
        // Format: 13-digit epoch millis (sortable as text through year 2286)
        // + 6-digit per-process counter for tie-breaking within the same ms.
        return String.format("%013d-%06d",
                clock.instant().toEpochMilli(), idCounter.incrementAndGet());
    }

    private long freeDiskBytesSafe() {
        try {
            return Files.getFileStore(bufferDir).getUsableSpace();
        } catch (IOException e) {
            // If we can't tell, assume worst case (zero free) so the buffer
            // refuses writes — better than over-committing.
            LOG.log(Level.WARNING, "Could not query free disk for " + bufferDir, e);
            return 0L;
        }
    }

    private static byte[] serialize(WorkerMetricBatch envelope) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (OutputStream gz = new GZIPOutputStream(baos)) {
            MAPPER.writeValue(gz, envelope);
        }
        return baos.toByteArray();
    }

    private static WorkerMetricBatch readEnvelope(Path file) throws IOException {
        try (InputStream gz = new GZIPInputStream(Files.newInputStream(file))) {
            return MAPPER.readValue(gz, WorkerMetricBatch.class);
        }
    }

    private static void deleteFileQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete buffer file " + file, e);
        }
    }

    /**
     * Configuration for {@link DiskBackedMetricsBuffer}. Defaults match the
     * K-3 spec; production wiring reads from {@code OrchestratorConfig}.
     *
     * @param maxBytes          total bytes cap on the buffer (default 20 MB)
     * @param maxFileBytes      per-file cap, defense in depth against
     *                          oversize envelopes (default 200 KB)
     * @param minFreeDiskBytes  free disk reserve below which writes are
     *                          refused (default 1 GB) — JMeter wins
     * @param maxAge            envelope TTL — older envelopes evict before
     *                          fresh ones do (default 6 hours)
     */
    public record DiskBackedMetricsBufferConfig(
            long maxBytes,
            long maxFileBytes,
            long minFreeDiskBytes,
            Duration maxAge
    ) {
        public DiskBackedMetricsBufferConfig {
            if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be > 0");
            if (maxFileBytes <= 0) throw new IllegalArgumentException("maxFileBytes must be > 0");
            if (maxFileBytes > maxBytes) {
                throw new IllegalArgumentException(
                        "maxFileBytes (" + maxFileBytes + ") must be <= maxBytes (" + maxBytes + ")");
            }
            if (minFreeDiskBytes < 0) throw new IllegalArgumentException("minFreeDiskBytes must be >= 0");
            if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
                throw new IllegalArgumentException("maxAge must be a positive duration");
            }
        }

        /** Spec-defined defaults for the K-3 metrics-buffer knobs. */
        public static DiskBackedMetricsBufferConfig defaults() {
            return new DiskBackedMetricsBufferConfig(
                    20L * 1024L * 1024L,        // 20 MB
                    200L * 1024L,                // 200 KB
                    1024L * 1024L * 1024L,       // 1 GB
                    Duration.ofHours(6));
        }
    }
}
