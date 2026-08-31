package com.perf.globalorchestrator.http;

import com.perf.globalorchestrator.domain.Region;
import com.perf.globalorchestrator.domain.RegionCapacity;
import com.perf.globalorchestrator.region.RegionRegistry;
import com.perf.globalorchestrator.region.RegionValidationService;
import com.perf.globalorchestrator.region.TestProvisionService;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.RegionRepository;
import com.perf.globalorchestrator.service.ClusterRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("RegionController — the cluster registry API (CLUSTER-CAPACITY)")
class RegionControllerTest {

    private static final String URL = "http://na-east-control-plane:30088";

    private RegionRepository repo;
    private RegionRegistry registry;
    private RegionValidationService validator;
    private TestProvisionService testProvision;
    private GroupCapacityRepository capacity;
    private PodRepository pods;
    private ClusterRegistryService clusters;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repo = mock(RegionRepository.class);
        registry = mock(RegionRegistry.class);
        validator = mock(RegionValidationService.class);
        testProvision = mock(TestProvisionService.class);
        capacity = mock(GroupCapacityRepository.class);
        pods = mock(PodRepository.class);
        clusters = mock(ClusterRegistryService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new RegionController(repo, registry, validator, testProvision, capacity, pods, clusters)).build();
    }

    private static Region row(String region) {
        return new Region(region, region + " DC", URL, 20,
                Instant.parse("2026-08-30T12:00:00Z"), null, null, null, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("GET /status joins the registry row, the probe verdict, reservations and worker counts")
    void statusJoinsEverySource() throws Exception {
        when(repo.findAll()).thenReturn(List.of(row("na-east")));
        when(capacity.reservedByRegion()).thenReturn(Map.of("na-east", 12));
        when(pods.regionCapacities()).thenReturn(List.of(new RegionCapacity("na-east", 7, 5, 0)));
        when(registry.statusOf("na-east")).thenReturn(Optional.of(
                new com.perf.globalorchestrator.region.RegionStatus("na-east", URL, true, true,
                        Instant.parse("2026-08-30T12:01:00Z"), null, null)));

        mvc.perform(get("/api/v1/regions/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].region").value("na-east"))
                .andExpect(jsonPath("$[0].label").value("na-east DC"))
                .andExpect(jsonPath("$[0].maxWorkers").value(20))
                .andExpect(jsonPath("$[0].reservedWorkers").value(12))
                .andExpect(jsonPath("$[0].provisionedWorkers").value(7))
                .andExpect(jsonPath("$[0].reachable").value(true))
                .andExpect(jsonPath("$[0].probing").value(false));
    }

    @Test
    @DisplayName("POST registers only after the validation chain passes, and returns the checklist")
    void registerHappyPath() throws Exception {
        when(repo.find("na-east")).thenReturn(Optional.empty(), Optional.of(row("na-east")));
        when(validator.validate("na-east", URL)).thenReturn(List.of(
                new RegionValidationService.ClusterCheck("endpointReachable", true, "answered", "CLUSTER_UNREACHABLE")));

        mvc.perform(post("/api/v1/regions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"region\":\"na-east\",\"label\":\"na-east DC\",\"regionalUrl\":\"" + URL + "/\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cluster.region").value("na-east"))
                .andExpect(jsonPath("$.checks[0].name").value("endpointReachable"));

        verify(repo).insert("na-east", "na-east DC", URL, 20);   // trailing slash stripped; maxWorkers defaulted
        verify(registry).reload();
    }

    @Test
    @DisplayName("a failed validation is 422 with the code and the whole checklist; nothing is written")
    void registerValidationFailure() throws Exception {
        when(repo.find("na-east")).thenReturn(Optional.empty());
        when(validator.validate(eq("na-east"), anyString())).thenThrow(catchable());

        mvc.perform(post("/api/v1/regions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"region\":\"na-east\",\"label\":\"DC\",\"regionalUrl\":\"" + URL + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REGION_MISMATCH"))
                .andExpect(jsonPath("$.checks[1].ok").value(false));

        verify(repo, never()).insert(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    private static RegionValidationService.ClusterValidationException catchable() {
        return new RegionValidationService.ClusterValidationException(
                "na-east", "REGION_MISMATCH", "reports itself as 'na-west'",
                List.of(new RegionValidationService.ClusterCheck("endpointReachable", true, "answered", "CLUSTER_UNREACHABLE"),
                        new RegionValidationService.ClusterCheck("regionMatches", false, "reports itself as 'na-west'", "REGION_MISMATCH")));
    }

    @Test
    @DisplayName("a bad region id, a duplicate and an out-of-range maxWorkers are refused — the cap is 20")
    void registerRefusals() throws Exception {
        mvc.perform(post("/api/v1/regions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"region\":\"NA_EAST\",\"label\":\"DC\",\"regionalUrl\":\"" + URL + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        when(repo.find("na-east")).thenReturn(Optional.of(row("na-east")));
        mvc.perform(post("/api/v1/regions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"region\":\"na-east\",\"label\":\"DC\",\"regionalUrl\":\"" + URL + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLUSTER_EXISTS"));

        mvc.perform(post("/api/v1/regions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"region\":\"na-west\",\"label\":\"DC\",\"regionalUrl\":\"" + URL + "\",\"maxWorkers\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/regions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"region\":\"na-west\",\"label\":\"DC\",\"regionalUrl\":\"" + URL + "\",\"maxWorkers\":21}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at most 20")));
    }

    @Test
    @DisplayName("display name and regional URL are unique across clusters — 409 CLUSTER_NAME_TAKEN / CLUSTER_URL_TAKEN, nothing validated or written")
    void registerUniqueness() throws Exception {
        when(repo.find("na-south")).thenReturn(Optional.empty());
        when(repo.findByLabel("na-east DC")).thenReturn(Optional.of(row("na-east")));
        mvc.perform(post("/api/v1/regions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"region\":\"na-south\",\"label\":\"na-east DC\",\"regionalUrl\":\"http://na-south:30088\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLUSTER_NAME_TAKEN"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("na-east")));

        when(repo.findByLabel("NA South")).thenReturn(Optional.empty());
        when(repo.findByRegionalUrl(URL)).thenReturn(Optional.of(row("na-east")));
        mvc.perform(post("/api/v1/regions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"region\":\"na-south\",\"label\":\"NA South\",\"regionalUrl\":\"" + URL + "/\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLUSTER_URL_TAKEN"));

        verify(validator, never()).validate(anyString(), anyString());
        verify(repo, never()).insert(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("editing may keep the cluster's own name/URL but not steal another's")
    void updateUniqueness() throws Exception {
        when(repo.find("na-east")).thenReturn(Optional.of(row("na-east")));
        // Keeping its own label + URL is fine (the pre-check excludes self).
        when(repo.findByLabel("na-east DC")).thenReturn(Optional.of(row("na-east")));
        when(repo.findByRegionalUrl(URL)).thenReturn(Optional.of(row("na-east")));
        when(pods.regionCapacities()).thenReturn(List.of());
        when(clusters.update(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(0);
        mvc.perform(put("/api/v1/regions/na-east").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxWorkers\":18}"))
                .andExpect(status().isOk());

        // Taking na-west's display name is refused.
        when(repo.findByLabel("na-west DC")).thenReturn(Optional.of(row("na-west")));
        mvc.perform(put("/api/v1/regions/na-east").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"na-west DC\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLUSTER_NAME_TAKEN"));
    }

    @Test
    @DisplayName("PUT hands the guarded write to the locked service — its shrink refusal surfaces as 409; an unchanged URL skips re-validation")
    void updateShrinkGuard() throws Exception {
        when(repo.find("na-east")).thenReturn(Optional.of(row("na-east")));
        when(clusters.update("na-east", "na-east DC", URL, 10, false))
                .thenThrow(new ClusterRegistryService.ShrinkBelowReservedException("na-east", 10, 15));

        mvc.perform(put("/api/v1/regions/na-east").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxWorkers\":10}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLUSTER_SHRINK_BELOW_RESERVED"))
                .andExpect(jsonPath("$.reserved").value(15));

        when(pods.regionCapacities()).thenReturn(List.of());
        when(clusters.update("na-east", "na-east DC", URL, 18, false)).thenReturn(0);
        mvc.perform(put("/api/v1/regions/na-east").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxWorkers\":18}"))
                .andExpect(status().isOk());
        verify(validator, never()).validate(anyString(), anyString());
        // The unlocked repo write is never used — the ceiling invariant is the
        // service's, under SELECT … FOR UPDATE.
        verify(repo, never()).update(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("DELETE is refused while reservations or workers reference the cluster — the guard is the locked service's")
    void deleteGuard() throws Exception {
        when(repo.find("na-east")).thenReturn(Optional.of(row("na-east")));
        org.mockito.Mockito.doThrow(new ClusterRegistryService.ClusterInUseException("na-east", 2, 3))
                .when(clusters).delete("na-east");

        mvc.perform(delete("/api/v1/regions/na-east"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLUSTER_IN_USE"))
                .andExpect(jsonPath("$.reservations").value(2))
                .andExpect(jsonPath("$.workers").value(3));
        verify(repo, never()).delete(anyString());

        org.mockito.Mockito.doNothing().when(clusters).delete("na-east");
        mvc.perform(delete("/api/v1/regions/na-east")).andExpect(status().isNoContent());
        verify(clusters, org.mockito.Mockito.times(2)).delete("na-east");
    }

    @Test
    @DisplayName("testProvision passes the ROW's url (not the snapshot's), answers 202 then 409 while one runs, 404 for an unknown cluster")
    void testProvisionRoutes() throws Exception {
        when(repo.find("na-east")).thenReturn(Optional.of(row("na-east")));
        when(testProvision.start("na-east", URL)).thenReturn(true, false);

        mvc.perform(post("/api/v1/regions/na-east/testProvision"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.probing").value(true));
        mvc.perform(post("/api/v1/regions/na-east/testProvision"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROBE_IN_PROGRESS"));

        when(repo.find("ghost")).thenReturn(Optional.empty());
        mvc.perform(post("/api/v1/regions/ghost/testProvision"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLUSTER_NOT_REGISTERED"));
        verify(testProvision, never()).start(eq("ghost"), anyString());
    }

    @Test
    @DisplayName("a RUNNING probe surfaces as `probing`, never as a verdict — and it comes from the row, so every replica agrees")
    void runningProbeIsNotAVerdict() throws Exception {
        Region probing = new Region("na-east", "na-east DC", URL, 20, Instant.now(),
                Instant.now(), "RUNNING", null, Instant.now(), Instant.now());
        when(repo.findAll()).thenReturn(List.of(probing));
        when(capacity.reservedByRegion()).thenReturn(Map.of());
        when(pods.regionCapacities()).thenReturn(List.of());
        when(registry.statusOf("na-east")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/regions/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].probing").value(true))
                .andExpect(jsonPath("$[0].lastProbe").doesNotExist());
        // No in-JVM flag is consulted — a second replica reads the same row.
        verify(testProvision, never()).start(anyString(), anyString());
    }
}
