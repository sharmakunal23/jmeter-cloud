package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.client.TemplateBody;
import com.perf.globalorchestrator.http.FleetAllocationEntry;
import com.perf.globalorchestrator.http.StartRunRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The template → launch mapping the scheduler fires: byte-for-byte what a
 * click would send — allocation with per-worker snapshots, plugins, save
 * results; never a spin-on-shortfall.
 */
@DisplayName("CronFireService — template to StartRunRequest mapping")
class CronFireMappingTest {

    @Test
    void mapsEveryTemplateField() {
        TemplateBody t = new TemplateBody(
                2, "checkout-svc", "plan-blob", "data-blob",
                List.of(new FleetAllocationEntry("na-east", 2, List.of(Map.of("threads", "10")))),
                Map.of("USER_OFFSET", "100"),
                List.of("01HXC2VQK4M9N6P5T0YBX2WZ4Q"),
                true, null);
        StartRunRequest r = CronFireService.toStartRunRequest(t);
        assertThat(r.testPlanBlobId()).isEqualTo("plan-blob");
        assertThat(r.dataFilesBlobId()).isEqualTo("data-blob");
        assertThat(r.application()).isEqualTo("checkout-svc");
        assertThat(r.fleetAllocation()).containsExactlyElementsOf(t.fleetAllocation());
        assertThat(r.pluginIds()).containsExactly("01HXC2VQK4M9N6P5T0YBX2WZ4Q");
        assertThat(r.isSaveResults()).isTrue();
        assertThat(r.isSpinShortfall()).isFalse();
        assertThat(r.initiatedBy()).isNull();
    }

    @Test
    void v1TemplateWithoutPluginsMapsToEmpty() {
        TemplateBody t = new TemplateBody(
                1, "app", "plan-blob", null, List.of(), null, null, null, null);
        StartRunRequest r = CronFireService.toStartRunRequest(t);
        assertThat(r.pluginIds()).isEmpty();
        assertThat(r.isSaveResults()).isFalse();
    }
}
