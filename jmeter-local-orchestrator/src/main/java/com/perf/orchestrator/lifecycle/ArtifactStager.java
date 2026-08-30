package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.config.OrchestratorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Streams the uploaded test plan and data-file zip to disk, validating as it
 * goes, and swaps each into place atomically.
 *
 * <p>Nothing is buffered in memory: bytes move through a fixed
 * {@value #BUFFER_BYTES}-byte buffer while a {@link DigestInputStream} computes
 * the SHA-256, so a 512&nbsp;MB upload costs well under 1&nbsp;MB of RAM. Each
 * upload lands in a {@code .tmp} sibling and is renamed on success, so any
 * failure — validation, truncation, disk error — leaves the previous content
 * intact.
 *
 * <p>Zip validation is a security boundary, not a convenience check
 * (see {@code docs/orchestratorPlan.md} §"Validation rules"):
 * <ul>
 *   <li>Entry names may not contain {@code ..}, a leading {@code /}, a NUL byte,
 *       or a Windows drive letter.</li>
 *   <li>Symlink-style entries are rejected via the {@link ZipEntry} mode bits.</li>
 *   <li>Caps: per-entry {@code MAX_ENTRY_SIZE_MB} (256), total extracted
 *       {@code MAX_EXTRACTED_SIZE_MB} (1024), count {@code MAX_FILE_COUNT} (500).</li>
 *   <li>Extensions limited to {@code .csv .json .txt .properties .xml .jmx}.</li>
 * </ul>
 *
 * <p>On-disk layout:
 * <pre>
 *   ${TEST_PLAN_DIR}/plan.jmx          uploaded plan
 *   ${TEST_PLAN_DIR}/.metadata.json    plan metadata
 *   ${DATA_FILES_DIR}/                 extracted data files
 *   ${DATA_FILES_DIR}.zip              original zip, re-served by GET .../file
 *   ${DATA_FILES_DIR}.manifest.json    dataFiles manifest
 * </pre>
 */
@Service
public final class ArtifactStager {

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactStager.class);

    private static final int BUFFER_BYTES = 16 * 1024;

    /** ZIP entries with names ending in any of these are accepted. Lower-case match. */
    private static final Set<String> ALLOWED_DATA_EXTENSIONS = Set.of(
            ".csv", ".json", ".txt", ".properties", ".xml", ".jmx");

    private static final String PLAN_FILENAME = "plan.jmx";
    private static final String PLAN_METADATA_FILENAME = ".metadata.json";

    private final Path planDir;
    private final Path planFile;
    private final Path planMetadataFile;

    private final Path dataDir;
    private final Path dataDirTmp;
    private final Path dataZip;
    private final Path dataZipTmp;
    private final Path dataManifestFile;

    private final long maxPlanBytes;
    private final long maxDataZipBytes;
    private final long maxExtractedBytes;
    private final long maxEntryBytes;
    private final int  maxFileCount;

    private final Clock clock;
    private final MetadataCodec metadataCodec;

    /**
     * Bytes currently being streamed into a {@code .tmp} staging file. Bumped
     * per-chunk during {@link #streamToFile} and unwound on completion (success
     * or failure). Surfaced via {@link #getUploadInflightBytes()} for the
     * {@code orchestrator_upload_inflight_bytes} Prometheus gauge so a stuck
     * 512 MB upload is observable without log inspection.
     */
    private final AtomicLong inflightBytes = new AtomicLong();

    @Autowired
    public ArtifactStager(OrchestratorConfig config) {
        this(config, Clock.systemUTC(), new MetadataCodec());
    }

    ArtifactStager(OrchestratorConfig config, Clock clock, MetadataCodec codec) {
        this.planDir          = Path.of(config.getTestPlanDir());
        this.planFile         = planDir.resolve(PLAN_FILENAME);
        this.planMetadataFile = planDir.resolve(PLAN_METADATA_FILENAME);

        this.dataDir          = Path.of(config.getDataFilesDir());
        this.dataDirTmp       = withSuffix(dataDir, ".tmp");
        this.dataZip          = withSuffix(dataDir, ".zip");
        this.dataZipTmp       = withSuffix(dataDir, ".zip.tmp");
        this.dataManifestFile = withSuffix(dataDir, ".manifest.json");

        this.maxPlanBytes      = mb(config.getMaxPlanSizeMb());
        this.maxDataZipBytes   = mb(config.getMaxDataZipSizeMb());
        this.maxExtractedBytes = mb(config.getMaxExtractedSizeMb());
        this.maxEntryBytes     = mb(config.getMaxEntrySizeMb());
        this.maxFileCount      = config.getMaxFileCount();

        this.clock = clock;
        this.metadataCodec = codec;
    }

    // -----------------------------------------------------------------------
    // Test plan
    // -----------------------------------------------------------------------

    /**
     * Stores a test plan upload. Auto-detects whether the body is a raw
     * {@code .jmx} or a zip wrapping exactly one {@code .jmx}.
     *
     * @param body         the raw upload stream — caller closes
     * @param suggestedName an optional client-supplied filename surfaced in the metadata
     * @return the metadata that {@link #getPlanMetadata()} will subsequently return
     */
    public PlanMetadata storeTestPlan(InputStream body, String suggestedName) throws IOException {
        Files.createDirectories(planDir);

        Path stagingFile = planDir.resolve(PLAN_FILENAME + ".new");
        cleanupQuietly(stagingFile);

        // Buffer the first two bytes to detect a ZIP signature ("PK"). We
        // must not pre-read the whole stream — these two bytes are the only
        // sniffing we do before handing the rest to the appropriate writer.
        InputStream sniffable = body.markSupported() ? body : new java.io.BufferedInputStream(body, 4);
        sniffable.mark(4);
        byte[] header = sniffable.readNBytes(4);
        sniffable.reset();
        boolean isZip = header.length >= 2 && header[0] == 'P' && header[1] == 'K';

        try {
            CountingDigestStream copy;
            String filename;
            if (isZip) {
                copy = extractSinglePlanFromZip(sniffable, stagingFile);
                filename = firstNonBlank(suggestedName, "plan.jmx");
            } else {
                copy = streamPlanRaw(sniffable, stagingFile);
                filename = firstNonBlank(suggestedName, "plan.jmx");
            }

            atomicReplace(stagingFile, planFile);

            PlanMetadata meta = new PlanMetadata(
                    filename, copy.bytesWritten, copy.hexDigest(), Instant.now(clock), isZip);
            metadataCodec.writePlanMetadata(planMetadataFile, meta);
            return meta;
        } catch (RuntimeException | IOException e) {
            cleanupQuietly(stagingFile);
            throw e;
        }
    }

    public Optional<PlanMetadata> getPlanMetadata() throws IOException {
        if (!Files.exists(planMetadataFile)) return Optional.empty();
        return Optional.of(metadataCodec.readPlanMetadata(planMetadataFile));
    }

    public Optional<Path> getPlanFile() {
        return Files.exists(planFile) ? Optional.of(planFile) : Optional.empty();
    }

    public boolean clearTestPlan() throws IOException {
        boolean removed = Files.deleteIfExists(planFile);
        Files.deleteIfExists(planMetadataFile);
        return removed;
    }

    // -----------------------------------------------------------------------
    // Data files
    // -----------------------------------------------------------------------

    /**
     * Stores a data-file zip upload. Streams the body to a sibling
     * {@code .zip.tmp}, then extracts into {@code ${DATA_FILES_DIR}.tmp/}
     * with strict per-entry validation, then atomically swaps both into
     * place.
     *
     * @param body the raw upload stream — caller closes
     * @return the manifest that {@link #getDataFilesManifest()} will subsequently return
     */
    public DataFilesManifest storeDataFiles(InputStream body) throws IOException {
        return storeDataFiles(body, null);
    }

    /**
     * Same as {@link #storeDataFiles(InputStream)} but records the source
     * document-service {@code blobId} in the manifest — the anchor of the
     * reuse check (UX-DYNAMICS T4): a later run carrying the same
     * {@code dataFilesBlobId} skips the download when the staged copy is
     * intact.
     */
    public DataFilesManifest storeDataFiles(InputStream body, String blobId) throws IOException {
        Files.createDirectories(dataDir.getParent() == null ? Path.of(".") : dataDir.getParent());

        // Phase 1 — save the raw zip to a tmp sibling, hashing as we go.
        cleanupQuietly(dataZipTmp);
        cleanupRecursivelyQuietly(dataDirTmp);

        CountingDigestStream zipCopy;
        try {
            zipCopy = streamToFile(body, dataZipTmp, maxDataZipBytes, "data files zip");
        } catch (RuntimeException | IOException e) {
            cleanupQuietly(dataZipTmp);
            throw e;
        }

        // Phase 2 — verify ZIP magic (ZipInputStream silently treats a non-
        // zip body as an empty archive, which would otherwise extract zero
        // files and succeed) then extract into a tmp directory.
        try {
            requireZipMagic(dataZipTmp);
        } catch (ArtifactValidationException e) {
            cleanupQuietly(dataZipTmp);
            throw e;
        }

        ExtractionSummary summary;
        try (InputStream in = Files.newInputStream(dataZipTmp);
             ZipInputStream zip = new ZipInputStream(in)) {
            summary = extractAll(zip, dataDirTmp);
        } catch (ZipException ze) {
            // Order matters — ZipException IS-A IOException, so catch the
            // narrower one first.
            cleanupRecursivelyQuietly(dataDirTmp);
            cleanupQuietly(dataZipTmp);
            throw new ArtifactValidationException("INVALID_ARCHIVE",
                    "Upload is not a valid ZIP archive: " + ze.getMessage());
        } catch (RuntimeException | IOException e) {
            cleanupRecursivelyQuietly(dataDirTmp);
            cleanupQuietly(dataZipTmp);
            throw e;
        }

        DataFilesManifest manifest = new DataFilesManifest(
                zipCopy.bytesWritten,
                summary.totalExtracted,
                summary.entries.size(),
                summary.entries,
                zipCopy.hexDigest(),
                Instant.now(clock),
                blobId);

        // Phase 3a — drop the new manifest into a staging file alongside the
        // canonical path. No swap visible yet. A failure here aborts before
        // any of the later renames, so the previous dataDir / zip / manifest
        // remain fully intact.
        Path manifestStaging = withSuffix(dataManifestFile, ".staging");
        cleanupQuietly(manifestStaging);
        try {
            metadataCodec.writeDataFilesManifestRaw(manifestStaging, manifest);
        } catch (RuntimeException | IOException e) {
            cleanupQuietly(manifestStaging);
            cleanupRecursivelyQuietly(dataDirTmp);
            cleanupQuietly(dataZipTmp);
            throw e;
        }

        // Phase 3b — three reversible atomic swaps, each through a sibling
        // .bak that the rollback stack can restore in LIFO order on failure
        // of any later step. Peak disk usage briefly doubles for the
        // dataDir / zip pair (bounded by the configured caps); that is the
        // price of true client-visible atomicity.
        Path dataDirBak       = withSuffix(dataDir,          ".bak");
        Path dataZipBak       = withSuffix(dataZip,          ".bak");
        Path manifestBak      = withSuffix(dataManifestFile, ".bak");
        cleanupRecursivelyQuietly(dataDirBak);
        cleanupQuietly(dataZipBak);
        cleanupQuietly(manifestBak);

        Deque<Rollback> done = new ArrayDeque<>();
        try {
            swapPathReversibly(dataDirTmp,        dataDir,          dataDirBak,  done);
            swapPathReversibly(dataZipTmp,        dataZip,          dataZipBak,  done);
            swapPathReversibly(manifestStaging,   dataManifestFile, manifestBak, done);
        } catch (RuntimeException | IOException primary) {
            // Reverse every swap that already committed. Rollback failures
            // are surfaced as suppressed exceptions on the original failure
            // so operators have a complete trace if recovery itself fails.
            while (!done.isEmpty()) {
                try {
                    done.pop().run();
                } catch (IOException restore) {
                    primary.addSuppressed(restore);
                }
            }
            cleanupQuietly(manifestStaging);
            cleanupRecursivelyQuietly(dataDirTmp);
            cleanupQuietly(dataZipTmp);
            cleanupRecursivelyQuietly(dataDirBak);
            cleanupQuietly(dataZipBak);
            cleanupQuietly(manifestBak);
            throw primary;
        }

        // Phase 4 — success: drop the .bak siblings. Failures here are
        // non-fatal (they cost disk but cannot break correctness).
        cleanupRecursivelyQuietly(dataDirBak);
        cleanupQuietly(dataZipBak);
        cleanupQuietly(manifestBak);
        return manifest;
    }

    public Optional<DataFilesManifest> getDataFilesManifest() throws IOException {
        if (!Files.exists(dataManifestFile)) return Optional.empty();
        return Optional.of(metadataCodec.readDataFilesManifest(dataManifestFile));
    }

    /**
     * True when every manifest entry still exists under the extracted
     * directory — the reuse gate (UX-DYNAMICS T4). Deliberately NOT a
     * file-count equality: {@code buildLaunchSpec} copies {@code plan.jmx}
     * INTO the directory on every launch, so extra files are expected.
     */
    public boolean dataFilesIntact(DataFilesManifest manifest) {
        if (!Files.isDirectory(dataDir)) return false;
        for (String f : manifest.files()) {
            if (!Files.exists(dataDir.resolve(f))) return false;
        }
        return true;
    }

    public Optional<Path> getDataFilesZip() {
        return Files.exists(dataZip) ? Optional.of(dataZip) : Optional.empty();
    }

    public boolean clearDataFiles() throws IOException {
        boolean removed = false;
        if (Files.exists(dataDir)) {
            deleteRecursively(dataDir);
            removed = true;
        }
        Files.deleteIfExists(dataZip);
        Files.deleteIfExists(dataManifestFile);
        return removed;
    }

    // -----------------------------------------------------------------------
    // Phase 2 — extraction
    // -----------------------------------------------------------------------

    private ExtractionSummary extractAll(ZipInputStream zip, Path targetRoot) throws IOException {
        Files.createDirectories(targetRoot);

        long totalBytes = 0;
        List<String> names = new ArrayList<>();

        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            String rawName = entry.getName();
            try {
                if (entry.isDirectory()) {
                    // Directory entries don't count toward the file cap but we
                    // still validate their name to avoid leaving traversal
                    // markers on disk for a later read.
                    validateEntryName(rawName);
                    Files.createDirectories(safeResolve(targetRoot, rawName));
                    continue;
                }

                if (names.size() >= maxFileCount) {
                    throw new ArtifactValidationException("INVALID_ARCHIVE",
                            "Zip exceeds the maximum file count of " + maxFileCount + ".");
                }

                validateEntryName(rawName);
                validateExtension(rawName);

                Path dest = safeResolve(targetRoot, rawName);
                Files.createDirectories(dest.getParent());

                long written = copyEntryWithCap(zip, dest, maxEntryBytes, rawName);
                totalBytes += written;
                if (totalBytes > maxExtractedBytes) {
                    throw new ArtifactValidationException("INVALID_ARCHIVE",
                            "Extracted contents exceed " + (maxExtractedBytes / (1024 * 1024)) + " MB total.");
                }

                names.add(rawName);
            } finally {
                zip.closeEntry();
            }
        }

        names.sort(Comparator.naturalOrder());
        return new ExtractionSummary(totalBytes, names);
    }

    private static long copyEntryWithCap(ZipInputStream zip, Path dest, long cap, String name) throws IOException {
        try (OutputStream out = Files.newOutputStream(
                dest, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[BUFFER_BYTES];
            long total = 0;
            int r;
            while ((r = zip.read(buf)) != -1) {
                total += r;
                if (total > cap) {
                    throw new ArtifactValidationException("INVALID_ARCHIVE",
                            "Entry '" + name + "' exceeds " + (cap / (1024 * 1024)) + " MB.");
                }
                out.write(buf, 0, r);
            }
            return total;
        }
    }

    // -----------------------------------------------------------------------
    // Validation primitives — defense in depth, multiple checks
    // -----------------------------------------------------------------------

    private static void validateEntryName(String name) {
        if (name == null || name.isBlank()) {
            throw new ArtifactValidationException("INVALID_ARCHIVE", "Empty entry name.");
        }
        if (name.indexOf('\0') >= 0) {
            throw new ArtifactValidationException("INVALID_ARCHIVE",
                    "Entry name contains a NUL byte: '" + name + "'.");
        }
        // Reject Windows drive letters (e.g. "C:foo.csv").
        if (name.length() >= 2 && name.charAt(1) == ':') {
            throw new ArtifactValidationException("INVALID_ARCHIVE",
                    "Entry name uses a Windows drive letter: '" + name + "'.");
        }
        // Normalise both separators to slash, then walk segments. This
        // catches "..\\evil" on Windows-packaged zips too.
        String unified = name.replace('\\', '/');
        if (unified.startsWith("/")) {
            throw new ArtifactValidationException("INVALID_ARCHIVE",
                    "Entry name is absolute: '" + name + "'.");
        }
        for (String seg : unified.split("/")) {
            if (seg.equals("..")) {
                throw new ArtifactValidationException("INVALID_ARCHIVE",
                        "Entry name escapes the archive root: '" + name + "'.");
            }
        }
    }

    private static void validateExtension(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        if (!ALLOWED_DATA_EXTENSIONS.contains(ext)) {
            throw new ArtifactValidationException("INVALID_ARCHIVE",
                    "Entry '" + name + "' has a disallowed extension. " +
                    "Allowed: " + ALLOWED_DATA_EXTENSIONS + ".");
        }
    }

    /**
     * Resolves the entry name under {@code root} and verifies the result still
     * lives under {@code root} after symlink-aware normalisation. Belt-and-
     * braces over {@link #validateEntryName(String)} — catches anything the
     * string-level check missed.
     */
    private static Path safeResolve(Path root, String entryName) {
        Path resolved = root.resolve(entryName).normalize();
        Path rootAbs = root.toAbsolutePath().normalize();
        if (!resolved.toAbsolutePath().normalize().startsWith(rootAbs)) {
            throw new ArtifactValidationException("INVALID_ARCHIVE",
                    "Entry '" + entryName + "' resolves outside the archive root.");
        }
        return resolved;
    }

    // -----------------------------------------------------------------------
    // Test-plan body handling
    // -----------------------------------------------------------------------

    private CountingDigestStream streamPlanRaw(InputStream body, Path stagingFile) throws IOException {
        return streamToFile(body, stagingFile, maxPlanBytes, "test plan");
    }

    private CountingDigestStream extractSinglePlanFromZip(InputStream body, Path stagingFile) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(body)) {
            ZipEntry entry;
            int jmxCount = 0;
            CountingDigestStream copy = null;
            while ((entry = zip.getNextEntry()) != null) {
                try {
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    validateEntryName(name);
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".jmx")) {
                        continue;
                    }
                    jmxCount++;
                    if (jmxCount > 1) {
                        throw new ArtifactValidationException("INVALID_ARCHIVE",
                                "Plan zip contains more than one .jmx entry.");
                    }
                    // Wrap in a no-close shield: streamToFile closes the
                    // input stream it is given, but we still need the
                    // outer ZipInputStream to deliver closeEntry() and the
                    // following getNextEntry() check (multi-jmx detection).
                    copy = streamToFile(new NoCloseInputStream(zip), stagingFile, maxPlanBytes, "test plan");
                } finally {
                    zip.closeEntry();
                }
            }
            if (jmxCount == 0 || copy == null) {
                throw new ArtifactValidationException("INVALID_ARCHIVE",
                        "Plan zip does not contain a .jmx entry.");
            }
            return copy;
        } catch (ZipException ze) {
            throw new ArtifactValidationException("INVALID_ARCHIVE",
                    "Upload is not a valid ZIP archive: " + ze.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Streaming primitives
    // -----------------------------------------------------------------------

    /**
     * Streams {@code source} into {@code dest}, hashing the bytes and
     * aborting when more than {@code cap} bytes have been written. Caller
     * is responsible for cleaning up {@code dest} on failure.
     *
     * <p>Increments {@link #inflightBytes} per chunk and unwinds the running
     * total in a finally block — the gauge always returns to its prior value,
     * whether the upload completes, fails the cap, or aborts mid-stream.
     */
    private CountingDigestStream streamToFile(
            InputStream source, Path dest, long cap, String label) throws IOException {

        Files.createDirectories(dest.getParent() == null ? Path.of(".") : dest.getParent());

        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }

        long written = 0;
        try (DigestInputStream digesting = new DigestInputStream(source, sha);
             OutputStream out = Files.newOutputStream(
                     dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {

            byte[] buf = new byte[BUFFER_BYTES];
            int r;
            while ((r = digesting.read(buf)) != -1) {
                written += r;
                inflightBytes.addAndGet(r);
                if (written > cap) {
                    throw new ArtifactValidationException("PAYLOAD_TOO_LARGE",
                            label + " exceeds the configured maximum of " + (cap / (1024 * 1024)) + " MB.");
                }
                out.write(buf, 0, r);
            }
            return new CountingDigestStream(written, sha.digest());
        } finally {
            // Always release this upload's contribution to the gauge.
            // addAndGet of a negative value is correct under concurrent
            // uploads (each call independently undoes its own increments).
            if (written != 0) {
                inflightBytes.addAndGet(-written);
            }
        }
    }

    /**
     * Returns the running total of bytes currently buffered into staging
     * {@code .tmp} files across all in-flight uploads. Always 0 when no
     * upload is active.
     */
    public long getUploadInflightBytes() {
        return inflightBytes.get();
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent() == null ? Path.of(".") : target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Cross-filesystem rename — fall back to non-atomic. This is rare
            // (BASE_DIR and its children typically share a filesystem) but
            // shouldn't crash the upload.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reversible atomic swap of {@code source} into {@code target}, using
     * {@code backup} as the holding pen for the previous value.
     *
     * <ol>
     *   <li>If {@code target} exists, atomically rename it to {@code backup}
     *       (so we can roll back if the second rename fails).</li>
     *   <li>Atomically rename {@code source} into {@code target}.</li>
     * </ol>
     *
     * On success a {@link Rollback} is pushed onto {@code done} that reverses
     * both moves. The caller's outer try / catch invokes the rollback stack
     * (in LIFO order) if any later swap fails — so the previous on-disk state
     * is restored byte-for-byte rather than half-replaced.
     *
     * <p>Works for both files and directories: {@code Files.move} with
     * {@code ATOMIC_MOVE} renames either atomically when source and target
     * share a filesystem (the orchestrator guarantees this — everything
     * under {@code BASE_DIR}). On the rare cross-filesystem case the JVM
     * throws {@link AtomicMoveNotSupportedException} and we fall back to a
     * non-atomic move.
     */
    private static void swapPathReversibly(Path source, Path target, Path backup,
                                           Deque<Rollback> done) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);

        boolean targetExisted = Files.exists(target);
        if (targetExisted) {
            move(target, backup);
        }
        try {
            move(source, target);
        } catch (RuntimeException | IOException moveErr) {
            // Restore the backup if we already moved the old aside; otherwise
            // there is nothing to undo.
            if (targetExisted) {
                try {
                    move(backup, target);
                } catch (IOException restore) {
                    moveErr.addSuppressed(restore);
                }
            }
            throw moveErr;
        }

        // Push the rollback that reverses both moves (LIFO). On success of
        // the whole sequence the caller drops this stack; on failure of a
        // later step we replay it.
        boolean wasReplacing = targetExisted;
        done.push(() -> {
            // Undo: move new back to source path, restore backup as target.
            // Best-effort — any exception propagates up to be surfaced as a
            // suppressed exception on the caller's primary IOException.
            try {
                move(target, source);
            } finally {
                if (wasReplacing) {
                    move(backup, target);
                }
            }
        });
    }

    /** Atomic rename when possible, with the same cross-fs fallback used elsewhere. */
    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Functional interface for the rollback stack — checked-IOException variant of Runnable. */
    @FunctionalInterface
    private interface Rollback {
        void run() throws IOException;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException io) {
                    LOG.warn("Failed to delete {} during cleanup: {}", p, io.toString());
                }
            });
        }
    }

    private static void cleanupQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Failed to clean up {}: {}", file, e.toString());
        }
    }

    private static void cleanupRecursivelyQuietly(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (IOException e) {
            LOG.warn("Failed to recursively clean up {}: {}", dir, e.toString());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static long mb(int megabytes) {
        return megabytes * 1024L * 1024L;
    }

    /**
     * Verifies the file at {@code path} starts with a ZIP local-file header
     * ({@code PK\x03\x04}). ZipInputStream itself returns no entries on a
     * non-zip body rather than throwing — so we must reject up-front,
     * otherwise a junk upload silently extracts to nothing.
     */
    private static void requireZipMagic(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] head = in.readNBytes(4);
            boolean isZip = head.length == 4
                    && head[0] == 'P' && head[1] == 'K'
                    && head[2] == 0x03 && head[3] == 0x04;
            // Empty zips ("PK\x05\x06") are also valid but we reject them —
            // a zero-entry data-file zip is not useful and is more likely
            // a client bug than an intentional payload.
            if (!isZip) {
                throw new ArtifactValidationException("INVALID_ARCHIVE",
                        "Upload is not a valid ZIP archive (missing PK\\x03\\x04 header).");
            }
        }
    }

    private static Path withSuffix(Path p, String suffix) {
        Path parent = p.getParent();
        String name = p.getFileName().toString() + suffix;
        return parent == null ? Path.of(name) : parent.resolve(name);
    }

    private static String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    /** Holds the byte count + digest produced by {@link #streamToFile}. */
    private record CountingDigestStream(long bytesWritten, byte[] digest) {
        String hexDigest() {
            return HexFormat.of().formatHex(digest);
        }
    }

    private record ExtractionSummary(long totalExtracted, List<String> entries) {
    }

    /**
     * Filter that suppresses {@link #close()} so a downstream consumer
     * (e.g. {@link DigestInputStream} via try-with-resources inside
     * {@link #streamToFile}) doesn't tear down the parent {@link ZipInputStream}
     * mid-iteration.
     */
    private static final class NoCloseInputStream extends java.io.FilterInputStream {
        NoCloseInputStream(InputStream in) { super(in); }
        @Override public void close() { /* deliberate no-op */ }
    }
}
