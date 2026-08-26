package com.perf.orchestrator.http;

import com.perf.orchestrator.lifecycle.ArtifactStager;
import com.perf.orchestrator.lifecycle.PlanMetadata;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REST surface for the singleton test plan: {@code POST/GET/GET-file/DELETE
 * /api/v1/testPlan}.
 *
 * <p>The controller is a thin shell — all validation, atomic-swap and extraction logic lives in
 * {@link ArtifactStager}; status codes match {@code api/openapi.yaml};
 * exceptions are mapped to JSON envelopes by {@link GlobalErrorHandler}.
 *
 * <h2>Upload contract</h2>
 * Uploads stream directly from {@link HttpServletRequest#getInputStream()}
 * — no multipart parsing, no in-memory buffering. The {@code Content-Type}
 * is restricted to {@code application/octet-stream} (a raw {@code .jmx})
 * or {@code application/zip} (a {@code .zip} wrapping a single
 * {@code .jmx}). The legacy multipart {@code form-data} path is
 * <b>not</b> brought forward — clients that previously used
 * {@code curl -F file=@plan.jmx} switch to
 * {@code curl --data-binary @plan.jmx -H "Content-Type: application/octet-stream"}.
 *
 * <p>The optional {@code X-Filename} header still names the uploaded file
 * for the metadata response.
 *
 * <h2>TEST_RUNNING gate</h2>
 * {@code POST} and {@code DELETE} short-circuit with 409 when
 * {@link TestRunGate#isRunning()} reports true — the orchestrator never
 * mutates plan state under a live run.
 */
@RestController
public final class TestPlanController {

    private final ArtifactStager stager;
    private final TestRunGate gate;

    public TestPlanController(ArtifactStager stager, TestRunGate gate) {
        this.stager = stager;
        this.gate = gate;
    }

    @PostMapping(
            path = "/api/v1/testPlan",
            consumes = {MediaType.APPLICATION_OCTET_STREAM_VALUE, "application/zip"})
    public ResponseEntity<?> upload(HttpServletRequest request,
                                    @RequestHeader(value = "X-Filename", required = false)
                                    String suggestedName) throws IOException {
        if (gate.isRunning()) {
            return testRunningConflict("Cannot replace the test plan while a test is in progress.");
        }
        try (InputStream body = request.getInputStream()) {
            PlanMetadata meta = stager.storeTestPlan(body, suggestedName);
            return ResponseEntity.status(HttpStatus.CREATED).body(planJson(meta));
        }
    }

    @GetMapping("/api/v1/testPlan")
    public ResponseEntity<?> metadata() throws IOException {
        Optional<PlanMetadata> meta = stager.getPlanMetadata();
        if (meta.isEmpty()) {
            return notFound();
        }
        return ResponseEntity.ok(planJson(meta.get()));
    }

    @GetMapping(path = "/api/v1/testPlan/file", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> download() throws IOException {
        Optional<Path> file = stager.getPlanFile();
        if (file.isEmpty()) {
            return notFound();
        }
        Path path = file.get();
        Resource body = new InputStreamResource(Files.newInputStream(path));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(path))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + path.getFileName() + "\"")
                .body(body);
    }

    @DeleteMapping("/api/v1/testPlan")
    public ResponseEntity<?> delete() throws IOException {
        if (gate.isRunning()) {
            return testRunningConflict("Cannot remove the test plan while a test is in progress.");
        }
        stager.clearTestPlan();
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
        body.put("message", "No test plan has been uploaded.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    private static Map<String, Object> planJson(PlanMetadata meta) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filename",   meta.filename());
        out.put("sizeBytes",  meta.sizeBytes());
        out.put("sha256",     meta.sha256());
        out.put("uploadedAt", meta.uploadedAt().toString());
        out.put("compressed", meta.compressed());
        return out;
    }
}
