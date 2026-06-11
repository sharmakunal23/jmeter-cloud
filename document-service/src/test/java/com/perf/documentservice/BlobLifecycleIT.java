package com.perf.documentservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavior IT for the document-service's blob lifecycle.
 *
 * <p>Drives the real HTTP layer through {@link MockMvc}, against a real
 * {@link com.perf.documentservice.store.LocalFsBlobStore} writing under a
 * per-test temp directory. Covers the full happy path
 * (POST → GET metadata → GET bytes → DELETE → 404) plus the malformed-id
 * 404 contract that protects against path traversal.
 *
 * <p>Tests <strong>behavior</strong>, not exhaustive method coverage —
 * one test per user-visible scenario.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "documentService.localFs.rootPath=${java.io.tmpdir}/blobLifecycleIT/${random.uuid}"
})
@DisplayName("document-service blob lifecycle — behavior IT")
class BlobLifecycleIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    @DisplayName("POST → 201; GET metadata + bytes round-trip exactly; DELETE → 204; subsequent GET → 404")
    void roundTripAndDelete() throws Exception {
        byte[] payload = "Hello, jmeter-cloud — this is a test plan body.".getBytes();
        String expectedSha = sha256Hex(payload);

        // ── POST ───────────────────────────────────────────────────────
        MvcResult uploadResult = mvc.perform(post("/api/v1/blob")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blobId").exists())
                .andExpect(jsonPath("$.sizeBytes").value(payload.length))
                .andExpect(jsonPath("$.sha256").value(expectedSha))
                // Servlet adds ";charset=UTF-8" — assert prefix-match only.
                .andExpect(jsonPath("$.contentType",
                        org.hamcrest.Matchers.startsWith("application/octet-stream")))
                .andExpect(jsonPath("$.uploadedAt").exists())
                .andReturn();

        JsonNode body = mapper.readTree(uploadResult.getResponse().getContentAsString());
        String blobId = body.get("blobId").asText();
        assertThat(blobId).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]+");

        // ── GET /metadata ──────────────────────────────────────────────
        mvc.perform(get("/api/v1/blob/{id}/metadata", blobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blobId").value(blobId))
                .andExpect(jsonPath("$.sha256").value(expectedSha))
                .andExpect(jsonPath("$.sizeBytes").value(payload.length));

        // ── GET bytes ──────────────────────────────────────────────────
        mvc.perform(get("/api/v1/blob/{id}", blobId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-sha256", expectedSha))
                .andExpect(header().longValue("Content-Length", payload.length))
                .andExpect(content().bytes(payload));

        // ── DELETE ─────────────────────────────────────────────────────
        mvc.perform(delete("/api/v1/blob/{id}", blobId))
                .andExpect(status().isNoContent());

        // ── GET after DELETE → 404 ─────────────────────────────────────
        mvc.perform(get("/api/v1/blob/{id}", blobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BLOB_NOT_FOUND"));
    }

    @Test
    @DisplayName("Malformed blobId is rejected as 404 — guards against path traversal")
    void malformedBlobIdIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/blob/{id}", "../etc/passwd"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/blob/{id}", "tooshort"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE on a never-existed blob is idempotent — 204")
    void deleteIdempotency() throws Exception {
        // Fresh ULID we never uploaded — DELETE should still succeed (idempotent contract).
        String unknownId = com.perf.documentservice.store.Ulid.generate();
        mvc.perform(delete("/api/v1/blob/{id}", unknownId))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Step 18 — X-Name/X-Type round-trip; GET /blob?type= filters listing")
    void taggingAndListing() throws Exception {
        // Scope this test's uploads with a unique application tag so it
        // stays order-independent against other tests in the class
        // (Spring's per-class root means shared state across @Tests).
        final String app = "test-tagging-step18";

        mvc.perform(post("/api/v1/blob")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Name", "plan-A")
                        .header("X-Description", "first plan")
                        .header("X-Type", "testPlan")
                        .header("X-Application", app)
                        .content("plan-A bytes".getBytes()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("plan-A"))
                .andExpect(jsonPath("$.description").value("first plan"))
                .andExpect(jsonPath("$.type").value("testPlan"));

        mvc.perform(post("/api/v1/blob")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Name", "plan-B")
                        .header("X-Type", "testPlan")
                        .header("X-Application", app)
                        .content("plan-B bytes".getBytes()))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/blob")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Name", "data-1")
                        .header("X-Type", "dataFiles")
                        .header("X-Application", app)
                        // UI-D3 polish — uploads tagged X-Type=dataFiles must
                        // start with the ZIP magic bytes. Use a minimal valid
                        // header here; the store doesn't unpack the archive.
                        .content(MIN_ZIP_HEADER))
                .andExpect(status().isCreated());

        // Filter by type=testPlan ∧ application=app → exactly 2 items.
        mvc.perform(get("/api/v1/blob?type=testPlan&application=" + app))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.name=='plan-A')]").exists())
                .andExpect(jsonPath("$.items[?(@.name=='plan-B')]").exists());

        // application alone → all three.
        mvc.perform(get("/api/v1/blob?application=" + app))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3));

        // Pagination — limit=1 returns one row, total still 3.
        mvc.perform(get("/api/v1/blob?application=" + app + "&limit=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    @DisplayName("Step 28 — X-Application round-trip; ?application= filters; /applications rolls up counts")
    void applicationTagging() throws Exception {
        // Two checkout-svc uploads, one search-svc upload, one untagged.
        upload("plan-checkout", "testPlan", "checkout-svc");
        upload("data-checkout", "dataFiles", "checkout-svc");
        upload("plan-search",   "testPlan", "search-svc");
        upload("plan-legacy",   "testPlan", null);

        // Listing filters AND together — checkout-svc + testPlan = 1 row.
        mvc.perform(get("/api/v1/blob?application=checkout-svc&type=testPlan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("plan-checkout"))
                .andExpect(jsonPath("$.items[0].application").value("checkout-svc"))
                .andExpect(jsonPath("$.items[0].type").value("testPlan"));

        // application filter alone — checkout-svc has 2 blobs.
        mvc.perform(get("/api/v1/blob?application=checkout-svc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        // /applications rollup — checkout-svc:2, search-svc:1, null:1.
        mvc.perform(get("/api/v1/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.application=='checkout-svc')].blobCount")
                        .value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$[?(@.application=='search-svc')].blobCount")
                        .value(org.hamcrest.Matchers.hasItem(1)));

        // X-Application > 64 chars → 400 INVALID_REQUEST.
        String tooLong = "a".repeat(65);
        mvc.perform(post("/api/v1/blob")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Application", tooLong)
                        .content(new byte[]{0x01}))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("UI-D3 polish — uploads tagged X-Type=dataFiles must be valid ZIPs (PK\\x03\\x04 magic)")
    void dataFilesUploadRequiresZipMagic() throws Exception {
        // 1) Plain text uploaded as dataFiles → 400 INVALID_ARCHIVE.
        mvc.perform(post("/api/v1/blob")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Name", "fake-data")
                        .header("X-Type", "dataFiles")
                        .content("data zip A bytes".getBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARCHIVE"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("PK")));

        // 2) Empty body uploaded as dataFiles → 400.
        mvc.perform(post("/api/v1/blob")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Type", "dataFiles")
                        .content(new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARCHIVE"));

        // 3) Real ZIP magic (4-byte signature + bytes) → 201 CREATED.
        // Minimum valid: PK\x03\x04 + the rest. The store doesn't
        // validate the full archive structure — UI-D3 polish just
        // requires the magic up front.
        byte[] zipMagic = new byte[] {
                0x50, 0x4B, 0x03, 0x04,
                0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        };
        mvc.perform(post("/api/v1/blob")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Name", "real-data")
                        .header("X-Type", "dataFiles")
                        .content(zipMagic))
                .andExpect(status().isCreated());

        // 4) Non-zip but tagged as testPlan → 201 (validation only fires
        // for dataFiles).
        mvc.perform(post("/api/v1/blob")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Name", "plan-X")
                        .header("X-Type", "testPlan")
                        .content("<jmeterTestPlan/>".getBytes()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("UI-D2 — ?download=true sets Content-Disposition with type-conventional filename")
    void downloadFlagSetsContentDisposition() throws Exception {
        // Upload three blobs with different X-Type values to exercise the
        // extension inference per type.
        String testPlanId = uploadAndReturnId("checkout-baseline", "testPlan", null);
        String dataFilesId = uploadAndReturnId("checkout-fixtures", "dataFiles", null);
        String resultsId  = uploadAndReturnId("nightly-2026-05-11", "result", null);

        // Default GET (no flag) → no Content-Disposition; the inline behavior
        // protects existing curl + orchestrator callers.
        mvc.perform(get("/api/v1/blob/{id}", testPlanId))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Disposition"));

        // ?download=true → attachment + .jmx for testPlan.
        mvc.perform(get("/api/v1/blob/{id}", testPlanId).param("download", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"checkout-baseline.jmx\""));

        // ?download=true → .zip for dataFiles.
        mvc.perform(get("/api/v1/blob/{id}", dataFilesId).param("download", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"checkout-fixtures.zip\""));

        // ?download=true → .jtl.gz for results.
        mvc.perform(get("/api/v1/blob/{id}", resultsId).param("download", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"nightly-2026-05-11.jtl.gz\""));
    }

    @Test
    @DisplayName("UI-D2 — ?download=true on a blob whose name already has an extension preserves it as-is")
    void downloadFilenameKeepsExistingExtension() throws Exception {
        String id = uploadAndReturnId("preBuilt.tar.gz", "other", null);
        mvc.perform(get("/api/v1/blob/{id}", id).param("download", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"preBuilt.tar.gz\""));
    }

    @Test
    @DisplayName("UI-D2 — ?download=true with no X-Name falls back to blobId+.bin")
    void downloadFilenameFallsBackToBlobIdWhenNameMissing() throws Exception {
        // Upload without X-Name / X-Type — gets the blobId.bin fallback.
        MvcResult uploadResult = mvc.perform(post("/api/v1/blob")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("anonymous payload".getBytes()))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = mapper.readTree(uploadResult.getResponse().getContentAsString());
        String blobId = body.get("blobId").asText();

        mvc.perform(get("/api/v1/blob/{id}", blobId).param("download", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"" + blobId + ".bin\""));
    }

    private String uploadAndReturnId(String name, String type, String application) throws Exception {
        byte[] body = "dataFiles".equals(type)
                ? MIN_ZIP_HEADER
                : (name + " bytes").getBytes();
        var req = post("/api/v1/blob")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Name", name)
                .header("X-Type", type)
                .content(body);
        if (application != null) req.header("X-Application", application);
        MvcResult result = mvc.perform(req).andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("blobId").asText();
    }

    private void upload(String name, String type, String application) throws Exception {
        // UI-D3 polish — pick a body shape that satisfies the per-type
        // server-side validation. dataFiles must start with the ZIP magic;
        // other types accept arbitrary bytes.
        byte[] body = "dataFiles".equals(type)
                ? MIN_ZIP_HEADER
                : (name + " bytes").getBytes();
        var req = post("/api/v1/blob")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Name", name)
                .header("X-Type", type)
                .content(body);
        if (application != null) req.header("X-Application", application);
        mvc.perform(req).andExpect(status().isCreated());
    }

    /** Minimum bytes that satisfy the dataFiles ZIP-magic check —
     *  PK\\x03\\x04 + 8 bytes of zeroed local-file-header. The store
     *  doesn't unpack the archive, so the trailing bytes don't matter. */
    private static final byte[] MIN_ZIP_HEADER = new byte[] {
            0x50, 0x4B, 0x03, 0x04,
            0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    };

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
