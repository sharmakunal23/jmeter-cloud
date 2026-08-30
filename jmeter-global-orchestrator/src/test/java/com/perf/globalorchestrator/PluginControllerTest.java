package com.perf.globalorchestrator;

import com.perf.globalorchestrator.client.DocumentServiceClient;
import com.perf.globalorchestrator.client.DocumentServiceClient.BlobMetadataView;
import com.perf.globalorchestrator.domain.Plugin;
import com.perf.globalorchestrator.http.PluginController;
import com.perf.globalorchestrator.repo.PluginRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The plugin library's HTTP contract: one version per name, content dedup, orphan-blob hygiene, in-use delete gate. */
@DisplayName("PluginController — HTTP contract + error mapping")
class PluginControllerTest {

    private static final String BLOB = "01HXC2VQK4M9N6P5T0YBX2WZ4Q";
    private static final String PLUGIN_ID = "01HXC2VQK4M9N6P5T0YBX2WZ51";
    private static final Plugin EXISTING = new Plugin(
            PLUGIN_ID, "jpgc-casutg", "3.1", "01HXC2VQK4M9N6P5T0YBX2WZ50", "sha-existing",
            2048, "jpgc-casutg.jar", null, "tester", Instant.parse("2026-08-30T00:00:00Z"));

    private PluginRepository repo;
    private DocumentServiceClient documents;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repo = mock(PluginRepository.class);
        documents = mock(DocumentServiceClient.class);
        mvc = MockMvcBuilders.standaloneSetup(new PluginController(repo, documents)).build();
    }

    private void blobIs(String type, String name, long size) {
        when(documents.fetchBlobMetadata(BLOB))
                .thenReturn(Optional.of(new BlobMetadataView(BLOB, size, "sha-new", name, type)));
    }

    private static String body(String name, String version) {
        return "{\"name\":\"" + name + "\",\"version\":\"" + version + "\",\"blobId\":\"" + BLOB + "\"}";
    }

    @Test
    @DisplayName("GET lists the library")
    void list() throws Exception {
        when(repo.findAll()).thenReturn(List.of(EXISTING));
        mvc.perform(get("/api/v1/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("jpgc-casutg"))
                .andExpect(jsonPath("$[0].version").value("3.1"));
    }

    @Test
    @DisplayName("POST registers — sha256/size/fileName come from the blob's metadata, actor from X-Actor")
    void register_happyPath() throws Exception {
        blobIs("plugin", "bzm-parallel.jar", 4096);
        when(repo.findByName("bzm-parallel")).thenReturn(Optional.empty());
        when(repo.findBySha256("sha-new")).thenReturn(Optional.empty());
        when(repo.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(post("/api/v1/plugins").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor", "kunal")
                        .content(body("bzm-parallel", "0.13")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("bzm-parallel"))
                .andExpect(jsonPath("$.sha256").value("sha-new"))
                .andExpect(jsonPath("$.sizeBytes").value(4096))
                .andExpect(jsonPath("$.fileName").value("bzm-parallel.jar"))
                .andExpect(jsonPath("$.createdBy").value("kunal"));
        verify(repo).insert(any());
    }

    @Test
    @DisplayName("a taken name is 409 PLUGIN_NAME_TAKEN with the existing version + the orphan upload deleted")
    void register_nameTaken() throws Exception {
        blobIs("plugin", "jpgc-casutg.jar", 4096);
        when(repo.findByName("jpgc-casutg")).thenReturn(Optional.of(EXISTING));
        when(repo.existsByBlobId(BLOB)).thenReturn(false);
        mvc.perform(post("/api/v1/plugins").contentType(MediaType.APPLICATION_JSON)
                        .content(body("jpgc-casutg", "9.9")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLUGIN_NAME_TAKEN"))
                .andExpect(jsonPath("$.existing.version").value("3.1"))
                .andExpect(jsonPath("$.orphanBlobDeleted").value(true));
        verify(documents).deleteBlob(BLOB);
        verify(repo, never()).insert(any());
    }

    @Test
    @DisplayName("duplicate content is 409 PLUGIN_CONTENT_DUPLICATE — and a registered blob is NEVER deleted")
    void register_contentDuplicate_neverDeletesRegisteredBlob() throws Exception {
        blobIs("plugin", "renamed.jar", 4096);
        when(repo.findByName("renamed")).thenReturn(Optional.empty());
        when(repo.findBySha256("sha-new")).thenReturn(Optional.of(EXISTING));
        // The caller passed a blobId a registry row references (e.g. re-registering
        // an existing plugin's own blob under a new name) — the guard must hold.
        when(repo.existsByBlobId(BLOB)).thenReturn(true);
        mvc.perform(post("/api/v1/plugins").contentType(MediaType.APPLICATION_JSON)
                        .content(body("renamed", "1.0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLUGIN_CONTENT_DUPLICATE"))
                .andExpect(jsonPath("$.existing.name").value("jpgc-casutg"))
                .andExpect(jsonPath("$.orphanBlobDeleted").value(false));
        verify(documents, never()).deleteBlob(BLOB);
    }

    @Test
    @DisplayName("a blob not uploaded with X-Type: plugin is 400 BLOB_NOT_PLUGIN")
    void register_blobNotPlugin() throws Exception {
        blobIs("other", "thing.jar", 4096);
        mvc.perform(post("/api/v1/plugins").contentType(MediaType.APPLICATION_JSON)
                        .content(body("thing", "1.0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BLOB_NOT_PLUGIN"));
    }

    @Test
    @DisplayName("a non-jar/zip file is 400 INVALID_PLUGIN_FILE")
    void register_invalidFile() throws Exception {
        blobIs("plugin", "notes.txt", 4096);
        mvc.perform(post("/api/v1/plugins").contentType(MediaType.APPLICATION_JSON)
                        .content(body("notes", "1.0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PLUGIN_FILE"));
    }

    @Test
    @DisplayName("DELETE is 204 and idempotent")
    void delete_idempotent() throws Exception {
        when(repo.countActiveRunsReferencing(PLUGIN_ID)).thenReturn(0);
        mvc.perform(delete("/api/v1/plugins/" + PLUGIN_ID)).andExpect(status().isNoContent());
        verify(repo).delete(PLUGIN_ID);
        mvc.perform(delete("/api/v1/plugins/" + PLUGIN_ID)).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE while a non-terminal run references it is 409 PLUGIN_IN_USE")
    void delete_inUse() throws Exception {
        when(repo.countActiveRunsReferencing(PLUGIN_ID)).thenReturn(2);
        mvc.perform(delete("/api/v1/plugins/" + PLUGIN_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLUGIN_IN_USE"));
        verify(repo, never()).delete(PLUGIN_ID);
    }
}
