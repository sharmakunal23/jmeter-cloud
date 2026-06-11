package com.perf.orchestrator.http;

import com.perf.orchestrator.lifecycle.ArtifactStager;
import com.perf.orchestrator.lifecycle.DataFilesManifest;
import com.perf.orchestrator.lifecycle.TestRunGate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REST surface for the singleton data-file zip:
 * {@code POST/GET/GET-file/DELETE /api/v1/dataFiles}.
 *
 * <p><b>Migrated to Spring MVC in Step 4.4d.</b> Same shape as
 * {@link TestPlanController} but with the larger {@code MAX_DATA_ZIP_SIZE_MB}
 * cap (default 512&nbsp;MB) and the manifest endpoint reporting the
 * extracted file list. {@code POST} streams from
 * {@link HttpServletRequest#getInputStream()}; the multipart {@code form-data}
 * upload path is <b>not</b> brought forward (see {@link TestPlanController}
 * javadoc for the curl-flag swap).
 *
 * <p>Validation failures from {@link ArtifactStager} surface via
 * {@link com.perf.orchestrator.lifecycle.ArtifactValidationException} and
 * are mapped to 400 / 413 by {@link GlobalErrorHandler}; this controller
 * only handles the {@code TEST_RUNNING} gate and the documented
 * {@code NO_FILE_EXISTS} 404 envelope inline.
 */
@RestController
public final class DataFilesController {

    private final ArtifactStager stager;
    private final TestRunGate gate;

    public DataFilesController(ArtifactStager stager, TestRunGate gate) {
        this.stager = stager;
        this.gate = gate;
    }

    @PostMapping(path = "/api/v1/dataFiles", consumes = "application/zip")
    public ResponseEntity<?> upload(HttpServletRequest request) throws IOException {
        if (gate.isRunning()) {
            return testRunningConflict("Cannot replace the data files while a test is in progress.");
        }
        try (InputStream body = request.getInputStream()) {
            DataFilesManifest manifest = stager.storeDataFiles(body);
            return ResponseEntity.status(HttpStatus.CREATED).body(manifestJson(manifest));
        }
    }

    @GetMapping("/api/v1/dataFiles")
    public ResponseEntity<?> manifest() throws IOException {
        Optional<DataFilesManifest> m = stager.getDataFilesManifest();
        if (m.isEmpty()) {
            return notFound();
        }
        return ResponseEntity.ok(manifestJson(m.get()));
    }

    @GetMapping(path = "/api/v1/dataFiles/file", produces = "application/zip")
    public ResponseEntity<?> download() throws IOException {
        Optional<Path> zip = stager.getDataFilesZip();
        if (zip.isEmpty()) {
            return notFound();
        }
        Path path = zip.get();
        Resource body = new InputStreamResource(Files.newInputStream(path));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(Files.size(path))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + path.getFileName() + "\"")
                .body(body);
    }

    @DeleteMapping("/api/v1/dataFiles")
    public ResponseEntity<?> delete() throws IOException {
        if (gate.isRunning()) {
            return testRunningConflict("Cannot remove the data files while a test is in progress.");
        }
        stager.clearDataFiles();
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Response helpers
    // -----------------------------------------------------------------------

    private static ResponseEntity<Map<String, String>> testRunningConflict(String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "TEST_RUNNING");
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private static ResponseEntity<Map<String, String>> notFound() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "NO_FILE_EXISTS");
        body.put("message", "No data files have been uploaded.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    private static Map<String, Object> manifestJson(DataFilesManifest m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zipSizeBytes",   m.zipSizeBytes());
        out.put("extractedBytes", m.extractedBytes());
        out.put("fileCount",      m.fileCount());
        out.put("files",          m.files());
        out.put("sha256",         m.sha256());
        out.put("uploadedAt",     m.uploadedAt().toString());
        return out;
    }
}
