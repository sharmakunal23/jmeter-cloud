package com.perf.orchestrator.buffer;

import com.perf.orchestrator.WorkerMetricBatch;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;

import java.io.ByteArrayInputStream;
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
 * Disk-backed {@link MetricsBuffer} — one gzipped Avro file per envelope under
 * {@code <bufferDir>/<id>.envelope.gz}, with an in-memory {@link ConcurrentSkipListMap}
 * index for O(log n) {@link #peekOldest} and O(1) {@link #delete}.
 *
 * <h2>Durability boundary</h2>
 * Every write follows: serialize envelope to Avro binary → gzip → write to
 * {@code <id>.envelope.gz.tmp} → {@code Files.move(ATOMIC_MOVE)} to
 * {@code <id>.envelope.gz}. The atomic rename is the durability boundary —
 * {@link #peekOldest} and the boot scrubber only ever see the final filename,
 * so a crash mid-write leaves an orphaned {@code .tmp} the scrubber removes.
 *
 * <h2>JMeter-considerate sizing knobs</h2>
 * All knobs come from {@link DiskBackedMetricsBufferConfig}. The
 * load-bearing one is {@code minFreeDiskBytes} — when free disk drops below
 * the threshold, new writes are refused regardless of buffer-cap state, so
 * JMeter always wins the disk-pressure battle.
 *
 * <h2>Eviction order</h2>
 * On every enqueue:
 * <ol>
 *   <li>If free disk &lt; {@code minFreeDiskBytes}: refuse, increment
 *       {@code dropsForLowDisk}, return empty.</li>
 *   <li>If gzipped size &gt; {@code maxFileBytes}: refuse, increment
 *       {@code dropsForOversize}, return empty.</li>
 *   <li>TTL sweep: drop envelopes older than {@code maxAge} (counter
 *       {@code dropsForAge}). Cheap O(buffered count) walk of the index.</li>
 *   <li>Drop-oldest until headroom exists for the new envelope (counter
 *       {@code dropsForCap}).</li>
 *   <li>Persist.</li>
 * </ol>
 *
 * <p>Step (1) is checked first because it's the only step that doesn't free
 * any space — if the disk is full, dropping older envelopes from the buffer
 * frees buffer cap but might not free disk inode/space if other tenants own it.
 *
 * <h2>Boot scrubber</h2>
 * On construction: scan {@code <bufferDir>}, delete {@code .tmp} files (orphaned
 * partial writes from a prior crash), index every {@code .envelope.gz} found.
 * Lets the dispatcher pick up where the previous process left off.
 */
public final class DiskBackedMetricsBuffer implements MetricsBuffer {

    private static final Logger LOG = Logger.getLogger(DiskBackedMetricsBuffer.class.getName());

    private static final String ENVELOPE_SUFFIX = ".envelope.gz";
    private static final String TMP_SUFFIX      = ".envelope.gz.tmp";
    private static final String META_SUFFIX     = ".meta";
    private static final String META_TMP_SUFFIX = ".meta.tmp";

    /** Avro reader/writer are thread-safe — share singletons. */
    private static final SpecificDatumWriter<WorkerMetricBatch> WRITER =
            new SpecificDatumWriter<>(WorkerMetricBatch.class);
    private static final SpecificDatumReader<WorkerMetricBatch> READER =
            new SpecificDatumReader<>(WorkerMetricBatch.class);

    private final Path bufferDir;
    private final DiskBackedMetricsBufferConfig cfg;
    private final Clock clock;
    private final AtomicLong idCounter = new AtomicLong();

    /**
     * Index of currently-buffered envelopes. ULID-style keys give chronological
     * order via lexicographic comparison; concurrent skip-list lets the
     * Micrometer gauge thread read {@link #depthBytes} / {@link #depthEnvelopes}
     * concurrently with dispatch-thread enqueue/delete.
     */
    private final ConcurrentSkipListMap<String, BufferedEnvelope> index = new ConcurrentSkipListMap<>();

    /** Cached running sum of file sizes — gauge reads avoid walking the index. */
    private final AtomicLong totalBytes = new AtomicLong();

    // -----------------------------------------------------------------------
    // Counters (Micrometer)
    // -----------------------------------------------------------------------

    private final Counter cEnqueued;
    private final Counter cDeleted;
    private final Counter cDropsForCap;
    private final Counter cDropsForAge;
    private final Counter cDropsForLowDisk;
    private final Counter cDropsForOversize;
    private final Counter cBootRecovered;
    private final Counter cBootOrphansRemoved;

    public DiskBackedMetricsBuffer(Path bufferDir,
                                   DiskBackedMetricsBufferConfig cfg,
                                   MeterRegistry meterRegistry,
                                   Clock clock) {
        this.bufferDir = Objects.requireNonNull(bufferDir, "bufferDir cannot be null");
        this.cfg = Objects.requireNonNull(cfg, "cfg cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        Objects.requireNonNull(meterRegistry, "meterRegistry cannot be null");

        this.cEnqueued          = counter(meterRegistry, "enqueued",
                "Envelopes successfully persisted to disk.");
        this.cDeleted           = counter(meterRegistry, "deleted",
                "Envelopes removed after successful publish.");
        this.cDropsForCap       = counter(meterRegistry, "dropsForCap",
                "Envelopes evicted because buffer reached its byte cap.");
        this.cDropsForAge       = counter(meterRegistry, "dropsForAge",
                "Envelopes evicted because they exceeded the age TTL.");
        this.cDropsForLowDisk   = counter(meterRegistry, "dropsForLowDisk",
                "Envelopes refused at enqueue because free disk was below threshold (JMeter wins).");
        this.cDropsForOversize  = counter(meterRegistry, "dropsForOversize",
                "Envelopes refused because their gzipped size exceeded maxFileBytes.");
        this.cBootRecovered     = counter(meterRegistry, "bootRecovered",
                "Envelopes recovered from disk on boot (carryover from previous process).");
        this.cBootOrphansRemoved = counter(meterRegistry, "bootOrphansRemoved",
                "Orphaned .tmp files removed on boot (partial writes from a prior crash).");

        Gauge.builder("metricsBuffer.depth.bytes", this, DiskBackedMetricsBuffer::depthBytes)
                .description("Total bytes currently buffered (sum of .envelope.gz file sizes).")
                .register(meterRegistry);
        Gauge.builder("metricsBuffer.depth.envelopes", this, DiskBackedMetricsBuffer::depthEnvelopes)
                .description("Envelope count currently buffered.")
                .register(meterRegistry);

        try {
            Files.createDirectories(bufferDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create buffer dir " + bufferDir, e);
        }
        bootScrub();
    }

    private static Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder("metricsBuffer." + name)
                .description(description)
                .register(registry);
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
                if (name.endsWith(TMP_SUFFIX) || name.endsWith(META_TMP_SUFFIX)) {
                    try {
                        Files.deleteIfExists(entry);
                        orphans++;
                    } catch (IOException e) {
                        LOG.log(Level.WARNING, "bootScrub: could not delete orphan " + entry, e);
                    }
                } else if (name.endsWith(ENVELOPE_SUFFIX)) {
                    String id = name.substring(0, name.length() - ENVELOPE_SUFFIX.length());
                    try {
                        long size = Files.size(entry);
                        Instant when = Files.getLastModifiedTime(entry).toInstant();
                        WorkerMetricBatch env = readEnvelope(entry);
                        String topic = readTopicSidecar(id);
                        BufferedEnvelope handle = new BufferedEnvelope(id, entry, size, when, env, topic);
                        index.put(id, handle);
                        totalBytes.addAndGet(size);
                        recovered++;
                    } catch (Exception e) {
                        // Avro can throw AvroRuntimeException (not IOException) on
                        // malformed payloads. Broad catch keeps boot resilient.
                        LOG.log(Level.WARNING, "bootScrub: could not load " + entry + " — deleting", e);
                        try {
                            Files.deleteIfExists(entry);
                            Files.deleteIfExists(bufferDir.resolve(
                                    name.substring(0, name.length() - ENVELOPE_SUFFIX.length()) + META_SUFFIX));
                        } catch (IOException ignored) { /* nothing to do */ }
                    }
                }
                // Note: orphan .meta files (envelope.gz missing) are left alone
                // — the next enqueue cycle's tmp cleanup will not collect them,
                // but they are tiny and harmless. Operator can clear bufferDir
                // manually if it ever becomes an issue.
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "bootScrub: directory walk failed for " + bufferDir, e);
        }
        if (recovered > 0) {
            cBootRecovered.increment(recovered);
            LOG.info(() -> String.format(
                    "bootScrub: recovered %d envelopes (%d bytes) from %s",
                    index.size(), totalBytes.get(), bufferDir));
        }
        if (orphans > 0) {
            cBootOrphansRemoved.increment(orphans);
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
    public synchronized Optional<BufferedEnvelope> enqueue(WorkerMetricBatch envelope, String topic) {
        Objects.requireNonNull(envelope, "envelope must be non-null");
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must be non-blank");
        }

        // Step 1 — Free-disk reservation (JMeter wins)
        long freeDisk = freeDiskBytesSafe();
        if (freeDisk < cfg.minFreeDiskBytes()) {
            cDropsForLowDisk.increment();
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
            cDropsForOversize.increment();
            return Optional.empty();
        }

        // Step 3 — TTL sweep (cheap O(buffered count) walk; usually a no-op)
        ttlSweep();

        // Step 4 — Drop-oldest until there is room for the new envelope
        evictOldestUntilHeadroom(payload.length);

        // Step 5 — Persist. Order: write sidecar first (so peek never finds an
        // envelope without its meta), then envelope.gz. The envelope's atomic
        // rename remains the durability boundary; if we crash after the meta
        // rename but before the envelope rename, the boot scrubber tolerates
        // an orphan .meta. The reverse — envelope without meta — would be a
        // routing bug, so we order writes to avoid it.
        String id = nextId();
        Path metaTmp    = bufferDir.resolve(id + META_TMP_SUFFIX);
        Path metaTarget = bufferDir.resolve(id + META_SUFFIX);
        Path tmp        = bufferDir.resolve(id + TMP_SUFFIX);
        Path target     = bufferDir.resolve(id + ENVELOPE_SUFFIX);
        try {
            Files.writeString(metaTmp, topic, java.nio.charset.StandardCharsets.UTF_8);
            Files.move(metaTmp, metaTarget, StandardCopyOption.ATOMIC_MOVE);
            Files.write(tmp, payload);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { Files.deleteIfExists(metaTmp); } catch (IOException ignored) { /* nothing more */ }
            try { Files.deleteIfExists(metaTarget); } catch (IOException ignored) { /* nothing more */ }
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* nothing more */ }
            throw new UncheckedIOException("Failed to persist envelope to " + target, e);
        }

        BufferedEnvelope handle = new BufferedEnvelope(
                id, target, payload.length, clock.instant(), envelope, topic);
        index.put(id, handle);
        totalBytes.addAndGet(payload.length);
        cEnqueued.increment();
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
                cDropsForAge.increment();
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
            cDropsForCap.increment();
        }
    }

    /**
     * Reads the {@code <id>.meta} sidecar for {@code id}. Returns {@code null}
     * when missing — only happens for envelopes persisted by a pre-Phase-G
     * build before sidecars existed; the dispatcher drops such envelopes
     * rather than guess a topic.
     */
    private String readTopicSidecar(String id) {
        Path meta = bufferDir.resolve(id + META_SUFFIX);
        if (!Files.exists(meta)) {
            return null;
        }
        try {
            String topic = Files.readString(meta, java.nio.charset.StandardCharsets.UTF_8).trim();
            return topic.isBlank() ? null : topic;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read topic sidecar " + meta, e);
            return null;
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
        cDeleted.increment();
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
            BinaryEncoder enc = EncoderFactory.get().binaryEncoder(gz, null);
            WRITER.write(envelope, enc);
            enc.flush();
        }
        return baos.toByteArray();
    }

    private static WorkerMetricBatch readEnvelope(Path file) throws IOException {
        try (InputStream gz = new GZIPInputStream(Files.newInputStream(file))) {
            byte[] all = gz.readAllBytes();
            BinaryDecoder dec = DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(all), null);
            return READER.read(null, dec);
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
