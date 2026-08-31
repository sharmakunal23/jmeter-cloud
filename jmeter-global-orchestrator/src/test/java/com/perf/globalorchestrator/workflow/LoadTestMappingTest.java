package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.client.TemplateBody;
import com.perf.globalorchestrator.domain.JoinPolicy;
import com.perf.globalorchestrator.domain.LoadTestNode;
import com.perf.globalorchestrator.domain.NodePosition;
import com.perf.globalorchestrator.domain.RegionCount;
import com.perf.globalorchestrator.http.FleetAllocationEntry;
import com.perf.globalorchestrator.http.StartRunRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The node-pins-the-fleet, template-supplies-the-plan mapping. The rule that
 * matters: a template's per-worker property snapshots are where a run's
 * {@code -J} values live, so changing the worker count has to rebuild them
 * rather than reuse an allocation sized for a different fleet.
 */
@DisplayName("LoadTestTaskExecutor — node fleet over template fleet")
class LoadTestMappingTest {

    private static final NodePosition P = new NodePosition(0, 0);

    private static LoadTestNode node(List<RegionCount> fleet, Map<String, String> props, Boolean saveResults) {
        return new LoadTestNode("t", "Peak", P, JoinPolicy.ALL, "payments", "tpl",
                fleet, props, saveResults, null, 60);
    }

    private static TemplateBody template(List<FleetAllocationEntry> fleet, Map<String, String> globals) {
        return new TemplateBody(2, "payments", "plan-blob", "data-blob", fleet, globals,
                List.of("plugin-1"), true, "alice");
    }

    @Test
    @DisplayName("same fleet, no overrides — the template's per-worker snapshots are kept intact")
    void identicalFleetKeepsTemplateSnapshots() {
        // Worker 0 deliberately differs from worker 1; that authoring must survive.
        List<FleetAllocationEntry> templateFleet = List.of(new FleetAllocationEntry("na-east", 2,
                List.of(Map.of("threads", "10"), Map.of("threads", "90"))));
        StartRunRequest req = LoadTestTaskExecutor.toStartRunRequest(
                node(List.of(new RegionCount("na-east", 2)), Map.of(), null),
                template(templateFleet, Map.of("threads", "50")));

        assertThat(req.fleetAllocation()).isSameAs(templateFleet);
        assertThat(req.fleetAllocation().get(0).propertiesFor(0)).containsEntry("threads", "10");
        assertThat(req.fleetAllocation().get(0).propertiesFor(1)).containsEntry("threads", "90");
    }

    @Test
    @DisplayName("a different worker count rebuilds the snapshots from the template's globals")
    void differentCountRebuildsFromGlobals() {
        StartRunRequest req = LoadTestTaskExecutor.toStartRunRequest(
                node(List.of(new RegionCount("na-east", 3)), Map.of(), null),
                template(List.of(new FleetAllocationEntry("na-east", 2,
                        List.of(Map.of("threads", "10"), Map.of("threads", "90")))),
                        Map.of("threads", "50", "rampUp", "60")));

        assertThat(req.fleetAllocation()).hasSize(1);
        FleetAllocationEntry entry = req.fleetAllocation().get(0);
        assertThat(entry.count()).isEqualTo(3);
        // Every worker gets the template's globals — not a truncated copy of a 2-worker snapshot.
        for (int i = 0; i < 3; i++) {
            assertThat(entry.propertiesFor(i)).containsEntry("threads", "50").containsEntry("rampUp", "60");
        }
    }

    @Test
    @DisplayName("node properties overlay the template's globals")
    void nodePropertiesWin() {
        StartRunRequest req = LoadTestTaskExecutor.toStartRunRequest(
                node(List.of(new RegionCount("na-east", 1)), Map.of("threads", "200"), null),
                template(List.of(new FleetAllocationEntry("na-east", 1, List.of(Map.of("threads", "50")))),
                        Map.of("threads", "50", "rampUp", "60")));

        assertThat(req.fleetAllocation().get(0).propertiesFor(0))
                .containsEntry("threads", "200")
                .containsEntry("rampUp", "60");
    }

    @Test
    @DisplayName("multi-cluster fleets map one entry per cluster")
    void multiRegionFleet() {
        StartRunRequest req = LoadTestTaskExecutor.toStartRunRequest(
                node(List.of(new RegionCount("na-east", 2), new RegionCount("na-west", 3)), Map.of(), null),
                template(List.of(), Map.of()));

        assertThat(req.fleetAllocation()).extracting(FleetAllocationEntry::region, FleetAllocationEntry::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("na-east", 2),
                        org.assertj.core.groups.Tuple.tuple("na-west", 3));
    }

    @Test
    @DisplayName("the plan, data files and plugins always come from the template; saveResults falls back to it")
    void templateSuppliesThePlan() {
        TemplateBody tpl = template(List.of(), Map.of());
        StartRunRequest fromTemplate = LoadTestTaskExecutor.toStartRunRequest(
                node(List.of(new RegionCount("na-east", 1)), Map.of(), null), tpl);
        assertThat(fromTemplate.testPlanBlobId()).isEqualTo("plan-blob");
        assertThat(fromTemplate.dataFilesBlobId()).isEqualTo("data-blob");
        assertThat(fromTemplate.pluginIds()).containsExactly("plugin-1");
        assertThat(fromTemplate.application()).isEqualTo("payments");
        assertThat(fromTemplate.isSaveResults()).isTrue();     // the template's

        StartRunRequest overridden = LoadTestTaskExecutor.toStartRunRequest(
                node(List.of(new RegionCount("na-east", 1)), Map.of(), Boolean.FALSE), tpl);
        assertThat(overridden.isSaveResults()).isFalse();      // the node's
    }

    @Test
    @DisplayName("a shortfall is filled: the launch pre-flight already approved this budget")
    void spinsToFillAShortfall() {
        StartRunRequest req = LoadTestTaskExecutor.toStartRunRequest(
                node(List.of(new RegionCount("na-east", 1)), Map.of(), null), template(List.of(), Map.of()));
        assertThat(req.isSpinShortfall()).isTrue();
        assertThat(req.isRefreshDataFiles()).isFalse();
    }
}
