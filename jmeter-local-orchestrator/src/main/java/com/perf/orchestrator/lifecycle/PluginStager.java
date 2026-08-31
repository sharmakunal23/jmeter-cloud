package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.storage.ArtifactSource;
import com.perf.orchestrator.storage.FetchSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Stages run-scoped JMeter plugin jars under {@code ${PLUGINS_DIR}} as a
 * content-addressed cache: blob ids are immutable ULIDs, so an existing
 * {@code <blobId>.jar} (or bundle dir {@code <blobId>/}) is reused without a
 * download. The JMeter install itself is never touched — the staged jars ride
 * {@code -Jsearch_paths}.
 *
 * <p>Bundle extraction mirrors {@link ArtifactStager}'s zip discipline
 * (tmp + atomic rename, entry-name validation, byte/count caps) but is
 * stricter: entries must be flat (depth 1) and {@code .jar} only. The cache
 * is bounded after each staging pass — oldest-mtime entries are evicted,
 * never the current request's set (single-run-per-worker makes this race-free).
 */
public final class PluginStager {

    private static final Logger LOG = LoggerFactory.getLogger(PluginStager.class);

    /** Mirrors {@code PluginSpec.FILE_NAME_PATTERN} — see the ';' note in {@link #validateBundleEntryName}. */
    private static final java.util.regex.Pattern BUNDLE_ENTRY_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");

    private static final int BUFFER_BYTES = 16 * 1024;

    private final Path pluginsRoot;
    private final long maxPluginBytes;
    private final int cacheMaxEntries;
    private final long cacheMaxBytes;
    private final int maxBundleJarCount;

    public PluginStager(OrchestratorConfig config) {
        this.pluginsRoot       = Path.of(config.getPluginsDir());
        this.maxPluginBytes    = config.getMaxPluginSizeMb() * 1024L * 1024L;
        this.cacheMaxEntries   = config.getPluginsCacheMaxEntries();
        this.cacheMaxBytes     = config.getPluginsCacheMaxBytes();
        this.maxBundleJarCount = config.getMaxFileCount();
    }

    /**
     * Ensures every spec is staged, downloading only what the cache lacks,
     * then bounds the cache. IO failures propagate as {@link IOException}
     * (the caller maps to {@code ARTIFACT_FETCH_FAILED} 502); a malformed
     * bundle throws {@link ArtifactValidationException} (→ 400).
     */
    public void stage(ArtifactSource source, String runId, List<PluginSpec> specs) throws IOException {
        if (specs.isEmpty()) return;
        Files.createDirectories(pluginsRoot);
        for (PluginSpec spec : specs) {
            Path target = targetOf(spec);
            boolean cached = spec.isBundle() ? Files.isDirectory(target) : Files.isRegularFile(target);
            if (cached) {
                LOG.info("plugin cache hit for {} ({}) — download skipped", spec.fileName(), spec.blobId());
                try {
                    // True LRU: a hit refreshes mtime so hot plugins outlive
                    // cold ones in the sweep (otherwise it is FIFO-by-stage-time).
                    Files.setLastModifiedTime(target,
                            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                } catch (IOException ignored) {
                    // Best-effort — a failed touch only weakens eviction order.
                }
                continue;
            }
            Optional<InputStream> body = source.fetch(ArtifactSource.KIND_PLUGIN,
                    new FetchSpec(runId, Map.of("blobId", spec.blobId())));
            if (body.isEmpty()) {
                throw new IOException("plugin " + spec.fileName() + " (" + spec.blobId()
                        + ") is not cached and the configured ARTIFACT_SOURCE cannot fetch blobs"
                        + " — set ARTIFACT_SOURCE=DOCUMENT_SERVICE on this worker");
            }
            try (InputStream in = body.get()) {
                if (spec.isBundle()) {
                    stageBundle(in, target, spec);
                } else {
                    stageJar(in, target, spec);
                }
            }
            LOG.info("staged plugin {} ({}) at {}", spec.fileName(), spec.blobId(), target);
        }
        sweep(keepNames(specs));
    }

    /**
     * Deterministic, ordered jar paths for the launch command: each single-jar
     * spec's {@code <blobId>.jar}, then a bundle's jars sorted by name. Pure
     * function of the specs + disk — no hidden state on the run.
     */
    public List<String> resolveJars(List<PluginSpec> specs) throws IOException {
        List<String> out = new ArrayList<>();
        for (PluginSpec spec : specs) {
            Path target = targetOf(spec);
            if (spec.isBundle()) {
                try (Stream<Path> entries = Files.list(target)) {
                    entries.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                            .sorted()
                            .map(p -> p.toAbsolutePath().toString())
                            .forEach(out::add);
                }
            } else {
                out.add(target.toAbsolutePath().toString());
            }
        }
        return out;
    }

    private Path targetOf(PluginSpec spec) {
        return spec.isBundle()
                ? pluginsRoot.resolve(spec.blobId())
                : pluginsRoot.resolve(spec.blobId() + ".jar");
    }

    private static Set<String> keepNames(List<PluginSpec> specs) {
        Set<String> keep = new HashSet<>();
        for (PluginSpec s : specs) {
            keep.add(s.isBundle() ? s.blobId() : s.blobId() + ".jar");
        }
        return keep;
    }

    // -----------------------------------------------------------------------
    // Staging
    // -----------------------------------------------------------------------

    private void stageJar(InputStream in, Path target, PluginSpec spec) throws IOException {
        Path tmp = pluginsRoot.resolve(spec.blobId() + ".jar.tmp");
        try {
            copyWithCap(in, tmp, maxPluginBytes, spec.fileName());
            atomicMove(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Extracts a flat bundle of jars into {@code <blobId>/} via a tmp dir +
     * atomic rename. Mirrors {@link ArtifactStager}'s entry validation, made
     * stricter: no directories, no separators (depth 1), {@code .jar} only.
     */
    private void stageBundle(InputStream in, Path target, PluginSpec spec) throws IOException {
        Path tmpDir = pluginsRoot.resolve(spec.blobId() + ".tmpdir");
        deleteRecursively(tmpDir);
        Files.createDirectories(tmpDir);
        boolean moved = false;
        try (ZipInputStream zip = new ZipInputStream(in)) {
            long totalBytes = 0;
            int jarCount = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                try {
                    String name = entry.getName();
                    validateBundleEntryName(name, spec);
                    if (entry.isDirectory()) {
                        // validateBundleEntryName already rejected separators,
                        // so a directory entry can only be malformed metadata.
                        throw new ArtifactValidationException("INVALID_ARCHIVE",
                                "Plugin bundle '" + spec.fileName() + "' contains a directory entry '" + name + "'.");
                    }
                    if (++jarCount > maxBundleJarCount) {
                        throw new ArtifactValidationException("INVALID_ARCHIVE",
                                "Plugin bundle '" + spec.fileName() + "' exceeds " + maxBundleJarCount + " entries.");
                    }
                    Path dest = tmpDir.resolve(name);
                    long written = copyEntryWithCap(zip, dest, maxPluginBytes, name);
                    totalBytes += written;
                    if (totalBytes > maxPluginBytes) {
                        throw new ArtifactValidationException("INVALID_ARCHIVE",
                                "Plugin bundle '" + spec.fileName() + "' exceeds "
                                + (maxPluginBytes / (1024 * 1024)) + " MB extracted.");
                    }
                } finally {
                    zip.closeEntry();
                }
            }
            if (jarCount == 0) {
                throw new ArtifactValidationException("INVALID_ARCHIVE",
                        "Plugin bundle '" + spec.fileName() + "' contains no jars.");
            }
            atomicMove(tmpDir, target);
            moved = true;
        } catch (ZipException ze) {
            throw new ArtifactValidationException("INVALID_ARCHIVE",
                    "Plugin bundle '" + spec.fileName() + "' is not a valid ZIP archive: " + ze.getMessage());
        } finally {
            if (!moved) deleteRecursively(tmpDir);
        }
    }

    /** Depth-1, jar-only, path-safe — the {@link ArtifactStager} name rules, tightened. */
    private static void validateBundleEntryName(String name, PluginSpec spec) {
        if (name == null || name.isBlank()) {
            throw new ArtifactValidationException("INVALID_ARCHIVE",
                    "Plugin bundle '" + spec.fileName() + "' has an empty entry name.");
        }
        if (name.indexOf('\0') >= 0) {
            throw new ArtifactValidationException("INVALID_ARCHIVE",
                    "Plugin bundle entry name contains a NUL byte: '" + name + "'.");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new ArtifactValidationException("INVALID_ARCHIVE",
                    "Plugin bundle entries must be flat (no directories); got '" + name + "'.");
        }
        // The same rule as PluginSpec.fileName — jar names ride -Jsearch_paths
        // joined with ';', so ';' (or any other exotic char) in an entry name
        // would silently split the path list mid-run.
        if (!BUNDLE_ENTRY_PATTERN.matcher(name).matches()) {
            throw new ArtifactValidationException("INVALID_ARCHIVE",
                    "Plugin bundle entry '" + name + "' must match [A-Za-z0-9][A-Za-z0-9._-]* .");
        }
        if (name.length() >= 2 && name.charAt(1) == ':') {
            throw new ArtifactValidationException("INVALID_ARCHIVE",
                    "Plugin bundle entry name uses a Windows drive letter: '" + name + "'.");
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new ArtifactValidationException("INVALID_ARCHIVE",
                    "Plugin bundle entries must be .jar files; got '" + name + "'.");
        }
    }

    private static void copyWithCap(InputStream in, Path dest, long cap, String label) throws IOException {
        Files.createDirectories(dest.getParent());
        long written = 0;
        try (OutputStream out = Files.newOutputStream(
                dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[BUFFER_BYTES];
            int r;
            while ((r = in.read(buf)) != -1) {
                written += r;
                if (written > cap) {
                    throw new ArtifactValidationException("PAYLOAD_TOO_LARGE",
                            "Plugin '" + label + "' exceeds " + (cap / (1024 * 1024)) + " MB.");
                }
                out.write(buf, 0, r);
            }
        }
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
                            "Bundle entry '" + name + "' exceeds " + (cap / (1024 * 1024)) + " MB.");
                }
                out.write(buf, 0, r);
            }
            return total;
        }
    }

    private static void atomicMove(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // -----------------------------------------------------------------------
    // Cache bounding
    // -----------------------------------------------------------------------

    /**
     * Evicts oldest-mtime top-level entries until the cache fits both bounds,
     * never touching {@code keep} (the current request's set). Best-effort:
     * an eviction failure logs a WARN and never fails the run.
     */
    private void sweep(Set<String> keep) {
        record CacheEntry(Path path, long mtimeMs, long bytes) {}
        List<CacheEntry> entries = new ArrayList<>();
        long totalBytes = 0;
        try (Stream<Path> top = Files.list(pluginsRoot)) {
            for (Path p : (Iterable<Path>) top::iterator) {
                String name = p.getFileName().toString();
                if (keep.contains(name)) continue;
                // Crashed staging/eviction leftovers (*.tmpdir / *.evict.tmp)
                // are always garbage — collect them first, unconditionally.
                if (name.contains(".tmp")) {
                    try {
                        deleteRecursively(p);
                    } catch (IOException io) {
                        LOG.warn("plugin cache could not remove leftover {}: {}", p, io.toString());
                    }
                    continue;
                }
                long bytes = sizeOf(p);
                long mtime;
                try {
                    mtime = Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    mtime = 0;
                }
                entries.add(new CacheEntry(p, mtime, bytes));
                totalBytes += bytes;
            }
        } catch (IOException e) {
            LOG.warn("plugin cache sweep could not list {}: {}", pluginsRoot, e.toString());
            return;
        }
        for (String k : keep) {
            totalBytes += sizeOf(pluginsRoot.resolve(k));
        }
        int totalEntries = entries.size() + keep.size();
        entries.sort(Comparator.comparingLong(CacheEntry::mtimeMs));
        for (CacheEntry e : entries) {
            if (totalEntries <= cacheMaxEntries && totalBytes <= cacheMaxBytes) break;
            try {
                // Rename-then-delete: a crash mid-delete must never leave a
                // partial dir under the cache key — a later run would trust
                // it as a cache hit and launch with missing jars. The rename
                // is atomic; the doomed copy is *.evict.tmp, which the
                // leftover pass above garbage-collects.
                Path doomed = e.path().resolveSibling(e.path().getFileName() + ".evict.tmp");
                atomicMove(e.path(), doomed);
                deleteRecursively(doomed);
                totalEntries--;
                totalBytes -= e.bytes();
                LOG.info("plugin cache evicted {} ({} bytes)", e.path().getFileName(), e.bytes());
            } catch (IOException io) {
                LOG.warn("plugin cache could not evict {}: {}", e.path(), io.toString());
            }
        }
    }

    private static long sizeOf(Path p) {
        if (!Files.exists(p)) return 0;
        try (Stream<Path> walk = Files.walk(p)) {
            return walk.filter(Files::isRegularFile).mapToLong(f -> {
                try { return Files.size(f); } catch (IOException e) { return 0; }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(f);
            }
        }
    }
}
