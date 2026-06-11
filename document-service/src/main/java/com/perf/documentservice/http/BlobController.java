package com.perf.documentservice.http;

import com.perf.documentservice.store.BlobListing;
import com.perf.documentservice.store.BlobMetadata;
import com.perf.documentservice.store.BlobNotFoundException;
import com.perf.documentservice.store.BlobStore;
import com.perf.documentservice.store.Ulid;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * REST surface for the blob-store. Routes a request directly through the
 * configured {@link BlobStore} backend; no copying through Spring's MVC
 * {@code MultipartResolver}, so a 512-MB upload streams without
 * exhausting the heap.
 *
 * <p>camelCase route convention per the platform-wide rule.
 */
@RestController
@RequestMapping("/api/v1")
public class BlobController {

    private static final Logger LOG = LoggerFactory.getLogger(BlobController.class);

    private final BlobStore store;
    private final Counter uploads;
    private final Counter downloads;
    private final Counter deletes;
    private final Counter notFound;
    private final Timer uploadTimer;
    private final DistributionSummary uploadBytes;

    public BlobController(BlobStore store, MeterRegistry meterRegistry) {
        this.store = store;
        this.uploads = Counter.builder("documentService.blob.uploads")
                .description("Successful blob uploads.")
                .register(meterRegistry);
        this.downloads = Counter.builder("documentService.blob.downloads")
                .description("Successful blob downloads.")
                .register(meterRegistry);
        this.deletes = Counter.builder("documentService.blob.deletes")
                .description("Successful blob deletes (existing or absent).")
                .register(meterRegistry);
        this.notFound = Counter.builder("documentService.blob.notFound")
                .description("Requests that targeted an unknown blobId.")
                .register(meterRegistry);
        this.uploadTimer = Timer.builder("documentService.blob.upload.duration")
                .description("Wall-time per blob upload (sha + write + meta).")
                .publishPercentileHistogram()
                .register(meterRegistry);
        // SECURITY S-0 — distribution of accepted upload sizes. A spike in the
        // upper percentiles (or a flood of large bodies) is the disk-fill /
        // bandwidth-burn abuse signal; the alert rule watches the byte rate.
        this.uploadBytes = DistributionSummary.builder("security.upload.bytes")
                .description("Accepted blob upload sizes in bytes (S-0 abuse signal).")
                .baseUnit("bytes")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @PostMapping("/blob")
    public ResponseEntity<BlobMetadata> uploadBlob(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            // Step 18 — uploader-supplied tags. All optional; null when
            // the header is missing. Headers are case-insensitive.
            @RequestHeader(value = "X-Name",        required = false) String name,
            @RequestHeader(value = "X-Description", required = false) String description,
            @RequestHeader(value = "X-Type",        required = false) String type,
            // Step 28 — application tag. Trimmed; max 64 chars enforced
            // server-side so a malformed header can't poison the index.
            @RequestHeader(value = "X-Application", required = false) String application
    ) throws IOException {
        long startNs = System.nanoTime();
        String app = sanitizeApplication(application);
        description = sanitizeDescription(description);   // SECURITY S-7 — cap at 200 chars
        // UI-D3 polish: when X-Type=dataFiles, sniff the first 4 bytes for
        // the ZIP magic (PK\x03\x04). The local-orchestrator unzips
        // dataFiles before launching JMeter — a non-zip upload turns into
        // a confusing INVALID_ARCHIVE failure at run-launch (see the
        // "data zip A bytes" test fixture that leaked into production).
        // Catching it here gives a clean 400 with the upload metadata in
        // hand instead of a deferred run failure.
        try (InputStream in = "dataFiles".equals(type)
                ? requireZipMagic(request.getInputStream())
                : request.getInputStream()) {
            BlobMetadata meta = store.put(in, contentType, name, description, type, app);
            uploads.increment();
            uploadBytes.record(meta.sizeBytes());
            return ResponseEntity.status(HttpStatus.CREATED).body(meta);
        } finally {
            uploadTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
        // NotAZipException (thrown by requireZipMagic) bubbles out and is
        // turned into a 400 INVALID_ARCHIVE by the @ExceptionHandler below.
    }

    /**
     * Wraps the upload stream and verifies the first 4 bytes match the
     * ZIP local-file-header signature (PK\x03\x04 = 0x504B0304). Empty
     * uploads are also rejected. The stream is buffered with PushbackInputStream
     * so the magic bytes can be re-read by the downstream digest + writer.
     */
    private static InputStream requireZipMagic(InputStream raw) throws IOException {
        java.io.PushbackInputStream pb = new java.io.PushbackInputStream(raw, 4);
        byte[] magic = new byte[4];
        int read = pb.readNBytes(magic, 0, 4);
        if (read < 4 || magic[0] != 0x50 || magic[1] != 0x4B
                || magic[2] != 0x03 || magic[3] != 0x04) {
            throw new NotAZipException("Upload tagged X-Type=dataFiles is not a valid ZIP archive "
                    + "(missing PK\\x03\\x04 header). Got "
                    + (read == 0 ? "empty body" : "first bytes 0x" + hex(magic, read))
                    + ".");
        }
        pb.unread(magic, 0, read);
        return pb;
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append(String.format("%02X", b[i]));
        return sb.toString();
    }

    /** Internal — converted to 400 INVALID_ARCHIVE by the catch block above. */
    private static final class NotAZipException extends IOException {
        NotAZipException(String message) { super(message); }
    }

    /**
     * Spring's @ExceptionHandler dispatch consumes generic IOException
     * from the upload path. We want a friendlier 400 body when the
     * NotAZipException specifically fires.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(NotAZipException.class)
    public ResponseEntity<java.util.Map<String, String>> notAZip(NotAZipException e) {
        return ResponseEntity.badRequest().body(java.util.Map.of(
                "code", "INVALID_ARCHIVE",
                "message", e.getMessage()));
    }

    @GetMapping("/blob")
    public ResponseEntity<BlobListing> listBlobs(
            @RequestParam(name = "type",        required = false) String typeFilter,
            @RequestParam(name = "application", required = false) String applicationFilter,
            @RequestParam(name = "offset",      required = false, defaultValue = "0") int offset,
            @RequestParam(name = "limit",       required = false, defaultValue = "50") int limit
    ) throws IOException {
        // Cap limit so a malicious caller can't ask the backend to
        // materialise the whole bucket at once.
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return ResponseEntity.ok(store.list(typeFilter, applicationFilter,
                Math.max(0, offset), safeLimit));
    }

    /**
     * Step 28 — distinct application tags + blob counts. The launcher
     * polls this so the Application picker reflects what's been
     * uploaded as new apps come online.
     */
    @GetMapping("/applications")
    public ResponseEntity<List<BlobStore.ApplicationSummary>> listApplications() throws IOException {
        return ResponseEntity.ok(store.listApplications());
    }

    /**
     * SECURITY S-7 — server-side description cap (the UI's {@code maxLength=200}
     * is bypassable by a direct caller). Trims, drops empty, and TRUNCATES to
     * 200 chars rather than rejecting: a description is cosmetic, so silently
     * capping an over-long one beats failing the upload. React escapes the value
     * on render, so this is a payload-size guard, not an XSS fix.
     */
    static String sanitizeDescription(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    /** Trim, drop empty, cap at 64 chars. Returns null for absent / blank. */
    private static String sanitizeApplication(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException(
                    "X-Application header must be ≤ 64 chars (got " + trimmed.length() + ")");
        }
        return trimmed;
    }

    @GetMapping("/blob/{blobId:" + Ulid.PATTERN + "}")
    public ResponseEntity<InputStreamResource> getBlob(
            @PathVariable String blobId,
            @RequestParam(value = "download", required = false, defaultValue = "false") boolean download
    ) throws IOException {
        BlobMetadata meta = store.stat(blobId);   // throws BlobNotFoundException
        InputStream stream = store.open(blobId);
        downloads.increment();
        MediaType type = meta.contentType() != null
                ? MediaType.parseMediaType(meta.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        ResponseEntity.BodyBuilder rb = ResponseEntity.ok()
                .contentType(type)
                .contentLength(meta.sizeBytes())
                .header("X-blobId", meta.blobId())
                .header("X-sha256", meta.sha256());
        if (download) {
            // UI-D2 — when the operator clicks a Download link the browser
            // should save the file with a meaningful filename, not the raw
            // ULID. Inferred from {X-Name, X-Type} captured at upload time;
            // missing X-Name falls back to blobId so we always emit a name.
            String filename = inferDownloadFilename(meta);
            rb.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        }
        return rb.body(new InputStreamResource(stream));
    }

    /**
     * Save Results — download every worker's result for a run as one zip.
     * Result blobs are uploaded with {@code X-Type=result} and a name shaped
     * {@code results-{runId}-{workerId}.jtl.gz}; since a runId is a
     * hyphen-free ULID, {@code results-{runId}-} is an unambiguous name
     * prefix. We scan result blobs, filter by that prefix, and stream a zip
     * (one entry per worker). 404 when the run saved nothing.
     *
     * <p>Lives under {@code /api/v1/blob/...} so the UI's nginx proxy routes
     * it straight to document-service (the rest of {@code /api/v1} goes to the
     * global-orchestrator).
     */
    @GetMapping("/blob/run/{runId:" + Ulid.PATTERN + "}/archive")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            downloadRunResults(@PathVariable String runId) throws IOException {
        String prefix = "results-" + runId + "-";
        List<BlobMetadata> matches = new java.util.ArrayList<>();
        int offset = 0;
        final int page = 500;
        while (true) {
            BlobListing listing = store.list("result", null, offset, page);
            for (BlobMetadata m : listing.items()) {
                if (m.name() != null && m.name().startsWith(prefix)) matches.add(m);
            }
            if (listing.items().size() < page) break;   // last page
            offset += page;
        }
        if (matches.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        downloads.increment();
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = out -> {
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
                for (BlobMetadata m : matches) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(m.name()));
                    try (InputStream in = store.open(m.blobId())) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition", "attachment; filename=\"results-" + runId + ".zip\"")
                .body(body);
    }

    /**
     * Build the filename the browser should save under on a {@code ?download=true}
     * GET. {@code name} is the operator-supplied label ({@code X-Name});
     * {@code type} drives the conventional extension. The conventions match
     * what document-service already uses internally for staged files:
     * <ul>
     *   <li>testPlan → .jmx</li>
     *   <li>dataFiles → .zip</li>
     *   <li>result → .jtl.gz</li>
     *   <li>other (or unset) → .bin</li>
     * </ul>
     * If {@code name} already carries an extension we trust it; if it doesn't
     * we append the conventional one. Falls back to the blobId when no name
     * was uploaded so the download is never literally empty.
     */
    static String inferDownloadFilename(BlobMetadata meta) {
        String base = (meta.name() != null && !meta.name().isBlank()) ? meta.name() : meta.blobId();
        // Strip any quote / slash / control chars that would break the header.
        String safe = base.replaceAll("[\"\\\\/\\u0000-\\u001f\\u007f]", "_");
        if (safe.contains(".")) return safe;
        String ext = switch (meta.type() == null ? "" : meta.type()) {
            case "testPlan"  -> ".jmx";
            case "dataFiles" -> ".zip";
            case "result"    -> ".jtl.gz";
            default          -> ".bin";
        };
        return safe + ext;
    }

    @GetMapping("/blob/{blobId:" + Ulid.PATTERN + "}/metadata")
    public ResponseEntity<BlobMetadata> getBlobMetadata(@PathVariable String blobId) throws IOException {
        return ResponseEntity.ok(store.stat(blobId));
    }

    @DeleteMapping("/blob/{blobId:" + Ulid.PATTERN + "}")
    public ResponseEntity<Void> deleteBlob(@PathVariable String blobId) throws IOException {
        store.delete(blobId);
        deletes.increment();
        return ResponseEntity.noContent().build();
    }

    // ── error handling ─────────────────────────────────────────────────

    @ExceptionHandler(BlobNotFoundException.class)
    ResponseEntity<Map<String, Object>> handleNotFound(BlobNotFoundException e) {
        notFound.increment();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "code",    "BLOB_NOT_FOUND",
                "message", e.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<Map<String, Object>> handleIo(IOException e) {
        LOG.error("Blob I/O failure", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code",    "STORAGE_IO_ERROR",
                "message", "Backend I/O failure — see service logs for the stack trace."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> handleBadArg(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code",    "INVALID_REQUEST",
                "message", e.getMessage()));
    }
}
