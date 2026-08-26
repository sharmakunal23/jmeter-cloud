package com.perf.documentservice.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Filesystem-backed {@link BlobStore}, the default backend.
 *
 * <p>Layout under {@code rootPath}, sharded on the ULID's random suffix
 * ({@code blobId[22..23]} then {@code [24..25]}) so directory entry counts stay
 * bounded and {@code blobId} still resolves to a path without a metadata read:
 * <pre>
 *   {root}/{shard1}/{shard2}/{blobId}              — blob bytes
 *   {root}/{shard1}/{shard2}/{blobId}.meta.json    — sidecar metadata
 * </pre>
 *
 * <p>Writes are atomic: bytes land in a {@code .tmp} sibling, then
 * {@code Files.move(..., ATOMIC_MOVE)}. A crash mid-upload leaves an ignorable
 * {@code .tmp} and nothing at the blob's final path.
 */
@Component
@ConditionalOnProperty(name = "documentService.backend", havingValue = "local", matchIfMissing = true)
public class LocalFsBlobStore implements BlobStore {

    private static final Logger LOG = LoggerFactory.getLogger(LocalFsBlobStore.class);

    /** 8 KiB transfer buffer — same size Spring's StreamUtils default uses. */
    private static final int COPY_BUFFER = 8192;

    private final Path rootPath;
    private final ObjectMapper json;

    public LocalFsBlobStore(
            @Value("${documentService.localFs.rootPath:/var/lib/document-service/blobs}") String rootPath,
            ObjectMapper json) {
        this.rootPath = Path.of(rootPath);
        this.json = json;
    }

    @PostConstruct
    void ensureRoot() throws IOException {
        Files.createDirectories(rootPath);
        LOG.info("LocalFsBlobStore rooted at {}", rootPath);
    }

    @Override
    public BlobMetadata put(InputStream input, String contentType,
                            String name, String description, String type,
                            String application) throws IOException {
        String blobId = Ulid.generate();
        Path bytesPath = pathFor(blobId);
        Path tmpPath = bytesPath.resolveSibling(bytesPath.getFileName() + ".tmp");
        Files.createDirectories(bytesPath.getParent());

        MessageDigest sha = newSha256();
        long size;
        try (DigestInputStream dis = new DigestInputStream(input, sha)) {
            // Files.copy(InputStream, Path) doesn't return the count for
            // unbounded streams in older JDKs — track it ourselves through
            // a counting stream.
            size = transferTo(dis, tmpPath);
        }

        // Atomic-move the bytes into place. ATOMIC_MOVE may not be supported
        // across filesystems (e.g., a Docker bind mount on a different fs);
        // fall back to a non-atomic move + warn.
        try {
            Files.move(tmpPath, bytesPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            LOG.warn("ATOMIC_MOVE unsupported at {} — falling back to REPLACE_EXISTING. "
                    + "A crash mid-publish could expose a partially-renamed blob.", rootPath);
            Files.move(tmpPath, bytesPath, StandardCopyOption.REPLACE_EXISTING);
        }

        BlobMetadata meta = BlobMetadata.of(
                blobId, size, hex(sha.digest()), contentType, Instant.now(),
                name, description, type, application);
        writeMetadata(meta);
        return meta;
    }

    @Override
    public BlobListing list(String typeFilter, String applicationFilter,
                            int offset, int limit) throws IOException {
        List<BlobMetadata> all = readAllMetadata();
        // Filter on type + application (AND together).
        List<BlobMetadata> filtered = new ArrayList<>();
        for (BlobMetadata m : all) {
            if (typeFilter != null && !typeFilter.equals(m.type())) continue;
            if (applicationFilter != null) {
                if (applicationFilter.isEmpty()) {
                    if (m.application() != null) continue;  // empty filter = "no app tag"
                } else if (!applicationFilter.equals(m.application())) {
                    continue;
                }
            }
            filtered.add(m);
        }
        // Newest-first.
        filtered.sort(Comparator.comparing(
                BlobMetadata::uploadedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        int total = filtered.size();
        int from = Math.max(0, Math.min(offset, total));
        int to   = Math.max(from, Math.min(from + limit, total));
        List<BlobMetadata> page = new ArrayList<>(filtered.subList(from, to));
        return new BlobListing(page, total, offset, limit);
    }

    @Override
    public List<ApplicationSummary> listApplications() throws IOException {
        // Aggregate over the same metadata walk used by list(). Acceptable
        // for local-fs scale (a few thousand blobs); a sibling index would
        // be needed for tens-of-thousands.
        List<BlobMetadata> all = readAllMetadata();
        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (BlobMetadata m : all) {
            counts.merge(m.application(), 1L, Long::sum);
        }
        List<ApplicationSummary> rows = new ArrayList<>(counts.size());
        counts.forEach((app, n) -> rows.add(new ApplicationSummary(app, n)));
        // Sort: untagged (null) last; otherwise blobCount DESC, then app name.
        rows.sort((a, b) -> {
            if (a.application() == null && b.application() != null) return 1;
            if (a.application() != null && b.application() == null) return -1;
            int byCount = Long.compare(b.blobCount(), a.blobCount());
            if (byCount != 0) return byCount;
            if (a.application() == null) return 0;
            return a.application().compareTo(b.application());
        });
        return rows;
    }

    private List<BlobMetadata> readAllMetadata() throws IOException {
        // Walks the shard tree reading every sidecar — fine to a few thousand
        // blobs, which is what the local backend is for. S3 paginates natively.
        List<BlobMetadata> all = new ArrayList<>();
        if (Files.exists(rootPath)) {
            try (var stream = Files.walk(rootPath)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".meta.json"))
                      .forEach(p -> {
                          try (InputStream in = Files.newInputStream(p)) {
                              all.add(json.readValue(in, BlobMetadata.class));
                          } catch (IOException e) {
                              LOG.warn("Failed to read {} during list: {}", p, e.toString());
                          }
                      });
            }
        }
        return all;
    }

    @Override
    public InputStream open(String blobId) throws IOException {
        validateBlobId(blobId);
        Path p = pathFor(blobId);
        if (!Files.exists(p)) {
            throw new BlobNotFoundException(blobId);
        }
        return Files.newInputStream(p);
    }

    @Override
    public BlobMetadata stat(String blobId) throws IOException {
        validateBlobId(blobId);
        Path metaPath = metadataPathFor(blobId);
        if (!Files.exists(metaPath)) {
            throw new BlobNotFoundException(blobId);
        }
        try (InputStream in = Files.newInputStream(metaPath)) {
            return json.readValue(in, BlobMetadata.class);
        }
    }

    @Override
    public boolean delete(String blobId) throws IOException {
        validateBlobId(blobId);
        boolean bytesDeleted = Files.deleteIfExists(pathFor(blobId));
        Files.deleteIfExists(metadataPathFor(blobId));
        return bytesDeleted;
    }

    // ── internals ──────────────────────────────────────────────────────

    private void writeMetadata(BlobMetadata meta) throws IOException {
        Path metaPath = metadataPathFor(meta.blobId());
        Path tmpPath = metaPath.resolveSibling(metaPath.getFileName() + ".tmp");
        Files.createDirectories(metaPath.getParent());
        Files.write(tmpPath, json.writeValueAsBytes(meta));
        try {
            Files.move(tmpPath, metaPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmpPath, metaPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Resolves the blob bytes path. blobId is assumed valid. */
    Path pathFor(String blobId) {
        return rootPath.resolve(shard1(blobId))
                       .resolve(shard2(blobId))
                       .resolve(blobId);
    }

    Path metadataPathFor(String blobId) {
        return rootPath.resolve(shard1(blobId))
                       .resolve(shard2(blobId))
                       .resolve(blobId + ".meta.json");
    }

    private static String shard1(String blobId) { return blobId.substring(22, 24); }
    private static String shard2(String blobId) { return blobId.substring(24, 26); }

    private static void validateBlobId(String blobId) {
        if (!Ulid.isValid(blobId)) {
            // Reject early so a malformed id can't escape into Path.resolve()
            // (which is otherwise vulnerable to "../" traversal).
            throw new BlobNotFoundException(blobId);
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    private static long transferTo(InputStream in, Path out) throws IOException {
        long total = 0;
        byte[] buf = new byte[COPY_BUFFER];
        try (var os = Files.newOutputStream(out)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
            }
        }
        return total;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
