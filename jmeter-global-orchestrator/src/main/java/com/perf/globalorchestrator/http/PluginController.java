package com.perf.globalorchestrator.http;

import com.perf.globalorchestrator.client.DocumentServiceClient;
import com.perf.globalorchestrator.client.DocumentServiceClient.BlobMetadataView;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.Plugin;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.repo.PluginRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The global plugin library: one version per plugin name (upgrade = delete +
 * re-register), duplicate content rejected by sha256, bytes retained in
 * document-service even after a registry delete so historical runs and
 * scale-up joiners can always stage their snapshot.
 */
@RestController
@RequestMapping("/api/v1/plugins")
public class PluginController {

    private static final Logger LOG = LoggerFactory.getLogger(PluginController.class);

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,127}");
    private static final int VERSION_MAX = 64;
    private static final long MAX_SIZE_BYTES = 256L * 1024 * 1024;

    private final PluginRepository plugins;
    private final DocumentServiceClient documents;

    public PluginController(PluginRepository plugins, DocumentServiceClient documents) {
        this.plugins = plugins;
        this.documents = documents;
    }

    @GetMapping
    public List<Plugin> list() {
        return plugins.findAll();
    }

    @PostMapping
    public ResponseEntity<Plugin> register(
            @RequestBody RegisterPluginRequest req,
            @RequestHeader(value = "X-Actor", required = false) String actorHeader) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || !NAME_PATTERN.matcher(name).matches()) {
            throw new PluginValidationException("INVALID_PLUGIN_NAME",
                    "plugin name must match [A-Za-z0-9][A-Za-z0-9 ._-]{0,127}");
        }
        String version = req.version() == null ? "" : req.version().trim();
        if (version.isEmpty() || version.length() > VERSION_MAX) {
            throw new PluginValidationException("INVALID_PLUGIN_VERSION",
                    "plugin version must be 1–" + VERSION_MAX + " characters");
        }
        String blobId = req.blobId() == null ? "" : req.blobId().trim();
        if (!Ulid.isValid(blobId)) {
            throw new PluginValidationException("BLOB_NOT_FOUND",
                    "blobId is not a valid document-service blob id");
        }
        BlobMetadataView meta = documents.fetchBlobMetadata(blobId)
                .orElseThrow(() -> new PluginValidationException("BLOB_NOT_FOUND",
                        "no document-service blob " + blobId));
        if (!"plugin".equals(meta.type())) {
            throw new PluginValidationException("BLOB_NOT_PLUGIN",
                    "blob " + blobId + " has X-Type '" + meta.type() + "' — upload it with X-Type: plugin");
        }
        String fileName = meta.name();
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (!lower.endsWith(".jar") && !lower.endsWith(".zip")) {
            throw new PluginValidationException("INVALID_PLUGIN_FILE",
                    "plugin blob must be a .jar (single plugin) or a .zip bundle of jars; got '" + fileName + "'");
        }
        if (meta.sizeBytes() > MAX_SIZE_BYTES) {
            throw new PluginValidationException("PLUGIN_TOO_LARGE",
                    "plugin is " + meta.sizeBytes() + " bytes; the cap is " + MAX_SIZE_BYTES);
        }

        // One version per plugin: pre-check both unique keys, then insert
        // catching the race — DuplicateKeyException alone cannot say which
        // constraint fired, so the catch re-queries to compose the right 409.
        plugins.findByName(name).ifPresent(existing -> {
            throw conflict("PLUGIN_NAME_TAKEN",
                    "'" + name + "' already exists at version " + existing.version()
                    + " — delete it first to register a new version", existing, blobId);
        });
        plugins.findBySha256(meta.sha256()).ifPresent(existing -> {
            throw conflict("PLUGIN_CONTENT_DUPLICATE",
                    "this exact file is already registered as " + existing.name() + "@" + existing.version(),
                    existing, blobId);
        });
        Plugin created = new Plugin(Ulid.generate(), name, version, blobId, meta.sha256(),
                meta.sizeBytes(), fileName,
                (req.description() == null || req.description().isBlank()) ? null : req.description().trim(),
                Actor.fromHeader(actorHeader).name(), Instant.now());
        try {
            plugins.insert(created);
        } catch (DuplicateKeyException e) {
            Optional<Plugin> byName = plugins.findByName(name);
            if (byName.isPresent()) {
                throw conflict("PLUGIN_NAME_TAKEN",
                        "'" + name + "' already exists at version " + byName.get().version()
                        + " — delete it first to register a new version", byName.get(), blobId);
            }
            Plugin bySha = plugins.findBySha256(meta.sha256()).orElseThrow(() -> e);
            throw conflict("PLUGIN_CONTENT_DUPLICATE",
                    "this exact file is already registered as " + bySha.name() + "@" + bySha.version(),
                    bySha, blobId);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{pluginId:" + Ulid.PATTERN + "}")
    public ResponseEntity<Void> delete(@PathVariable String pluginId) {
        int active = plugins.countActiveRunsReferencing(pluginId);
        if (active > 0) {
            throw new PluginInUseException(pluginId, active);
        }
        // The blob is deliberately retained: historical runs and scale-up
        // joiners stage from the run row's snapshot, which outlives the registry.
        plugins.delete(pluginId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Builds the 409, deleting the caller's now-orphan upload best-effort —
     * but never when a registry row references that blob (the content-duplicate
     * path can be handed a registered plugin's own blobId).
     */
    private PluginConflictException conflict(String code, String message, Plugin existing, String uploadedBlobId) {
        boolean orphanDeleted = false;
        if (!plugins.existsByBlobId(uploadedBlobId)) {
            try {
                documents.deleteBlob(uploadedBlobId);
                orphanDeleted = true;
            } catch (RuntimeException e) {
                LOG.warn("orphan plugin blob {} could not be deleted after {}: {}",
                        uploadedBlobId, code, e.toString());
            }
        }
        return new PluginConflictException(code, message, existing, orphanDeleted);
    }

    static final class PluginValidationException extends RuntimeException {
        final String code;
        PluginValidationException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    static final class PluginConflictException extends RuntimeException {
        final String code;
        final transient Plugin existing;
        final boolean orphanBlobDeleted;
        PluginConflictException(String code, String message, Plugin existing, boolean orphanBlobDeleted) {
            super(message);
            this.code = code;
            this.existing = existing;
            this.orphanBlobDeleted = orphanBlobDeleted;
        }
    }

    static final class PluginInUseException extends RuntimeException {
        PluginInUseException(String pluginId, int active) {
            super("plugin " + pluginId + " is referenced by " + active + " non-terminal run(s)");
        }
    }

    @ExceptionHandler(PluginValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidation(PluginValidationException e) {
        return ResponseEntity.badRequest().body(Map.of("code", e.code, "message", e.getMessage()));
    }

    @ExceptionHandler(PluginConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(PluginConflictException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", e.code);
        body.put("message", e.getMessage());
        body.put("existing", Map.of(
                "pluginId", e.existing.pluginId(),
                "name", e.existing.name(),
                "version", e.existing.version()));
        body.put("orphanBlobDeleted", e.orphanBlobDeleted);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(PluginInUseException.class)
    public ResponseEntity<Map<String, String>> handleInUse(PluginInUseException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "PLUGIN_IN_USE", "message", e.getMessage()));
    }
}
