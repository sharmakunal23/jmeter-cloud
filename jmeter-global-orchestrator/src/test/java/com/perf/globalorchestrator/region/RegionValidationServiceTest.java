package com.perf.globalorchestrator.region;

import com.perf.globalorchestrator.client.RegionalClient;
import com.perf.globalorchestrator.region.RegionValidationService.ClusterCheck;
import com.perf.globalorchestrator.region.RegionValidationService.ClusterValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RegionValidationService — the add-cluster dry-run chain")
class RegionValidationServiceTest {

    private static final String URL = "http://na-east-control-plane:30088";

    private RegionalClient client;
    private RegionValidationService service;

    @BeforeEach
    void setUp() {
        client = mock(RegionalClient.class);
        service = new RegionValidationService(client);
    }

    private static RegionCapabilities caps(String region) {
        return new RegionCapabilities(region, "jmeter-cloud", "workers", "img:dev", 8080, "dev", 5, 4096L, "5Gi");
    }

    private static RegionalClient.ProvisioningCheckResult dryRun(boolean ok, RegionalClient.ProvisioningCheck... checks) {
        return new RegionalClient.ProvisioningCheckResult("na-east", "img:dev", ok, List.of(checks));
    }

    @Test
    @DisplayName("all green: every check comes back ok, regional checks folded in")
    void allGreen() {
        when(client.capabilities(URL)).thenReturn(caps("na-east"));
        when(client.provisioningCheck(URL)).thenReturn(dryRun(true,
                new RegionalClient.ProvisioningCheck("imageConfigured", true, "img:dev"),
                new RegionalClient.ProvisioningCheck("rbacPods", true, "pods: create, delete, get, list, watch allowed"),
                new RegionalClient.ProvisioningCheck("quotaHeadroom", true, "5 worker(s) fit")));

        List<ClusterCheck> checks = service.validate("na-east", URL);

        assertThat(checks).extracting(ClusterCheck::name)
                .containsExactly("endpointReachable", "regionMatches", "imageConfigured", "rbacPods", "quotaHeadroom");
        assertThat(checks).allMatch(ClusterCheck::ok);
    }

    @Test
    @DisplayName("a malformed URL fails INVALID_CLUSTER_URL without any HTTP call")
    void malformedUrl() {
        assertThatThrownBy(() -> service.validate("na-east", "ftp://nope"))
                .isInstanceOfSatisfying(ClusterValidationException.class, e -> {
                    assertThat(e.code).isEqualTo("INVALID_CLUSTER_URL");
                    assertThat(e.checks).hasSize(1);
                });
    }

    @Test
    @DisplayName("an unreachable endpoint fails CLUSTER_UNREACHABLE and carries the cause")
    void unreachable() {
        when(client.capabilities(URL)).thenThrow(new RegionUnavailableException("na-east", "connect refused"));
        ClusterValidationException e = catchThrowableOfType(ClusterValidationException.class,
                () -> service.validate("na-east", URL));
        assertThat(e.code).isEqualTo("CLUSTER_UNREACHABLE");
        assertThat(e.getMessage()).contains(URL).contains("connect refused");
    }

    @Test
    @DisplayName("an id mismatch fails REGION_MISMATCH naming both ids; later checks still run for the full checklist")
    void regionMismatch() {
        when(client.capabilities(URL)).thenReturn(caps("na-west"));
        when(client.provisioningCheck(URL)).thenReturn(dryRun(true,
                new RegionalClient.ProvisioningCheck("imageConfigured", true, "img:dev")));

        ClusterValidationException e = catchThrowableOfType(ClusterValidationException.class,
                () -> service.validate("na-east", URL));
        assertThat(e.code).isEqualTo("REGION_MISMATCH");
        assertThat(e.getMessage()).contains("'na-west'").contains("'na-east'");
        assertThat(e.checks).extracting(ClusterCheck::name)
                .contains("endpointReachable", "regionMatches", "imageConfigured");
    }

    @Test
    @DisplayName("regional dry-run failures map to stable codes: image → NO_WORKER_IMAGE, rbac → RBAC_DENIED, quota → QUOTA_EXHAUSTED")
    void regionalCheckCodes() {
        when(client.capabilities(URL)).thenReturn(caps("na-east"));
        when(client.provisioningCheck(URL)).thenReturn(dryRun(false,
                new RegionalClient.ProvisioningCheck("imageConfigured", true, "img:dev"),
                new RegionalClient.ProvisioningCheck("rbacPods", false, "ServiceAccount lacks pods verbs: create"),
                new RegionalClient.ProvisioningCheck("quotaHeadroom", false, "0 worker(s) fit")));

        ClusterValidationException e = catchThrowableOfType(ClusterValidationException.class,
                () -> service.validate("na-east", URL));
        assertThat(e.code).isEqualTo("RBAC_DENIED");   // first failing check wins
        assertThat(e.checks).filteredOn(c -> !c.ok())
                .extracting(ClusterCheck::code)
                .containsExactly("RBAC_DENIED", "QUOTA_EXHAUSTED");
    }
}
