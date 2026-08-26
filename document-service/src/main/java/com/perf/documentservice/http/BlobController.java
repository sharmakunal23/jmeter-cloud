package com.perf.documentservice.http;

import com.perf.documentservice.store.BlobListing;
import com.perf.documentservice.store.BlobMetadata;
import com.perf.documentservice.store.BlobNotFoundException;
import com.perf.documentservice.store.BlobStore;
import com.perf.documentservice.store.Ulid;
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

/**
 * REST surface for the blob store, mounted at {@code /api/v1}.
 *
 * <p>Uploads read the raw request body rather than going through Spring's
 * {@code MultipartResolver}, so a 512-MB artifact streams without exhausting
 * the heap. <b>Callers must POST a raw body</b> — a {@code multipart/form-data}
 * upload (curl {@code -F}) stores 0 bytes.
 */
@RestController
@RequestMapping("/api/v1")
public class BlobController {

    private static final Logger LOG = LoggerFactory.getLogger(BlobController.class);

    private final BlobStore store;

    public BlobController(BlobStore store) {
        this.store = store;
    }

    @PostMapping("/blob")
    public ResponseEntity<BlobMetadata> uploadBlob(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            // Optional uploader tags; null when the header is absent.
            @RequestHeader(value = "X-Name",        required = false) String name,
            @RequestHeader(value = "X-Description", required = false) String description,
            @RequestHeader(value = "X-Type",        required = false) String type,
            // Trimmed and capped at 64 chars server-side so a malformed
            // header can't poison the listing index.
            @RequestHeader(value = "X-Application", required = false) String application
    ) throws IOException {
        String app = sanitizeApplication(application);
        description = sanitizeDescription(description);   // capped at 200 chars
        // The local-orchestrator unzips dataFiles before launching JMeter, so a
        // non-zip upload only fails much later at run-launch. Checking the ZIP
        // magic here turns that deferred failure into a clean 400.
        try (InputStream in = "dataFiles".equals(type)
                ? requireZipMagic(request.getInputStream())
                : request.getInputStream()) {
            BlobMetadata meta = store.put(in, contentType, name, description, type, app);
            // The access log carries status and latency but not body size, so
            // this line is the only record of accepted upload sizes — the
            // disk-fill / bandwidth-burn abuse signal infra alerting watches.
            LOG.info("Blob uploaded: blobId={} type={} application={} sizeBytes={}",
                    meta.blobId(), type, app, meta.sizeBytes());
            return ResponseEntity.status(HttpStatus.CREATED).body(meta);
        }
        // NotAZipException bubbles out to notAZip() below, becoming a 400.
    }

    /**
     * Verifies the stream opens with the ZIP local-file-header signature
     * (PK\x03\x04), rejecting empty bodies too.
     *
     * <p>Returns a {@code PushbackInputStream} with the magic bytes unread, so
     * the downstream digest and writer still see the whole file.
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

    /** Converted to 400 {@code INVALID_ARCHIVE} by {@link #notAZip}. */
    private static final class NotAZipException extends IOException {
        NotAZipException(String message) { super(message); }
    }

    /**
     * Maps the zip-magic rejection to 400 {@code INVALID_ARCHIVE}. Needed
     * because {@code NotAZipException} extends {@link IOException}, which the
     * handler below would otherwise turn into a 500.
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
        // Capped so a caller can't ask the backend to materialise everything.
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return ResponseEntity.ok(store.list(typeFilter, applicationFilter,
                Math.max(0, offset), safeLimit));
    }

    /**
     * Distinct application tags with blob counts. The launcher polls this so its
     * Application picker reflects newly uploaded artifacts.
     */
    @GetMapping("/applications")
    public ResponseEntity<List<BlobStore.ApplicationSummary>> listApplications() throws IOException {
        return ResponseEntity.ok(store.listApplications());
    }

    /**
     * Caps a description at 200 chars server-side, since the UI's
     * {@code maxLength} is bypassable by a direct caller.
     *
     * <p>Truncates rather than rejects — a description is cosmetic, so silently
     * capping it beats failing the upload. This is a payload-size guard, not an
     * XSS fix; React escapes the value on render.
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
        MediaType type = meta.contentType() != null
                ? MediaType.parseMediaType(meta.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        ResponseEntity.BodyBuilder rb = ResponseEntity.ok()
                .contentType(type)
                .contentLength(meta.sizeBytes())
                .header("X-blobId", meta.blobId())
                .header("X-sha256", meta.sha256());
        if (download) {
            // Save under a meaningful filename rather than the raw ULID.
            String filename = inferDownloadFilename(meta);
            rb.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        }
        return rb.body(new InputStreamResource(stream));
    }

    /**
     * Streams every worker's saved result for one run as a single zip, one entry
     * per worker, or 404 when the run saved nothing.
     *
     * <p>Matching is by name prefix {@code results-{runId}-}, which is
     * unambiguous only because a runId is a hyphen-free ULID. The route sits
     * under {@code /api/v1/blob/} so the UI's nginx proxy sends it here — the
     * rest of {@code /api/v1} goes to the global-orchestrator.
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
     * Builds the filename for a {@code ?download=true} GET from the uploaded
     * {@code X-Name}, falling back to the blobId so a download is never
     * unnamed.
     *
     * <p>A name that already has an extension is trusted; otherwise {@code type}
     * picks one: testPlan → {@code .jmx}, dataFiles → {@code .zip}, result →
     * {@code .jtl.gz}, anything else → {@code .bin}.
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
        return ResponseEntity.noContent().build();
    }

    // ── error handling ─────────────────────────────────────────────────

    @ExceptionHandler(BlobNotFoundException.class)
    ResponseEntity<Map<String, Object>> handleNotFound(BlobNotFoundException e) {
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
