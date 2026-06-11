package com.perf.orchestrator.http;

import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.lifecycle.CurrentRun;
import com.perf.orchestrator.lifecycle.TestRunGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST surface for the singleton JTL output:
 * {@code GET /api/v1/results} (metadata),
 * {@code GET /api/v1/results/file?format=raw|zip} (stream),
 * {@code DELETE /api/v1/results}.
 *
 * <p><b>Migrated to Spring MVC in Step 4.4f.</b> Reads from
 * {@code ${RESULTS_DIR}}. Returns 404 when no JTL exists; the DELETE
 * endpoint short-circuits with 409 while a test is RUNNING (the JTL is
 * being written to and removing it would lose live data).
 *
 * <p>{@code DELETE} is idempotent — deleting twice returns 204 both times.
 * The semantic "nothing to remove" is logged at DEBUG; clients don't
 * need to distinguish.
 */
@RestController
public final class ResultsController {

    private static final Logger LOG = LoggerFactory.getLogger(ResultsController.class);

    private final OrchestratorConfig config;
    private final CurrentRun currentRun;
    private final TestRunGate gate;

    public ResultsController(OrchestratorConfig config, CurrentRun currentRun, TestRunGate gate) {
        this.config = config;
        this.currentRun = currentRun;
        this.gate = gate;
    }

    @GetMapping("/api/v1/results")
    public ResponseEntity<?> metadata() throws IOException {
        Path jtl = jtlPath();
        if (!Files.exists(jtl)) {
            return notFound("No JTL has been produced yet.");
        }

        CurrentRun.Snapshot snap = currentRun.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId",        snap.runId());
        body.put("filename",     jtl.getFileName().toString());
        body.put("sizeBytes",    Files.size(jtl));
        body.put("rowCount",     snap.rowsIngested()); // best-effort — same source as /test
        body.put("createdAt",    Files.getLastModifiedTime(jtl).toInstant().toString());
        body.put("completedAt",  snap.completedAt() == null ? null : snap.completedAt().toString());
        body.put("uploadState",  snap.uploadState());
        body.put("uploadTarget", snap.uploadTarget());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/v1/results/file")
    public ResponseEntity<?> download(
            @RequestParam(value = "format", defaultValue = "raw") String format) throws IOException {
        Path jtl = jtlPath();
        if (!Files.exists(jtl)) {
            return notFound("No JTL has been produced yet.");
        }

        // ?format=zip — stream the gzipped file the uploader produced (if any).
        // "zip" historically meant "gzipped" here — the controller emits .gz
        // bytes with content-type application/gzip; renaming the query value
        // is a wire break, so we keep it.
        if ("zip".equalsIgnoreCase(format)) {
            Path gz = jtl.resolveSibling("results.jtl.gz");
            if (!Files.exists(gz)) {
                return notFound("No gzipped JTL has been produced yet (auto-upload may be disabled).");
            }
            Resource body = new InputStreamResource(Files.newInputStream(gz));
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/gzip"))
                    .contentLength(Files.size(gz))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"results.jtl.gz\"")
                    .body(body);
        }

        Resource body = new InputStreamResource(Files.newInputStream(jtl));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .contentLength(Files.size(jtl))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"results.jtl\"")
                .body(body);
    }

    @DeleteMapping("/api/v1/results")
    public ResponseEntity<?> delete() throws IOException {
        if (gate.isRunning()) {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("error", "TEST_RUNNING");
            body.put("message", "Cannot delete results while a test is in progress.");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        Path jtl = jtlPath();
        Path gz  = jtl.resolveSibling("results.jtl.gz");
        boolean removedJtl = Files.deleteIfExists(jtl);
        Files.deleteIfExists(gz);
        if (!removedJtl) {
            // Idempotent contract — deleting twice is fine.
            LOG.debug("DELETE /results — no JTL at {}", jtl);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Resolves the JTL path for the run that produced the artifact.
     * WORKER-HYGIENE Phase A — results live at
     * {@code results/{runId}/results.jtl}. Returns the path under the
     * current run's id (the last run the orchestrator handled, even if
     * terminal). If no run has executed yet, returns the legacy flat
     * path so the controller's existing 404 branch fires cleanly.
     */
    private Path jtlPath() {
        Path base = Path.of(config.getResultsDir());
        String runId = currentRun.snapshot().runId();
        if (runId == null || runId.isBlank()) {
            return base.resolve("results.jtl");
        }
        return base.resolve(runId).resolve("results.jtl");
    }

    private static ResponseEntity<Map<String, String>> notFound(String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "NO_FILE_EXISTS");
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
}
