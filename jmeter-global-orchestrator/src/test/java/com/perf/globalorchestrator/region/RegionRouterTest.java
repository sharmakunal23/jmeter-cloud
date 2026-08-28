package com.perf.globalorchestrator.region;

import com.perf.globalorchestrator.client.WorkerRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegionRouterTest {

    private final RegionRouter router = new RegionRouter(new RegionRegistry(
            new RegionProperties("na-east=http://na-east-control-plane:30088,na-west")));

    @Test
    @DisplayName("a routed region dials through its relay; a direct or unknown region dials the worker's baseUrl")
    void dials() {
        assertThat(router.dial(new WorkerRef("na-east", "payments-na-east-worker-1", "http://payments-na-east-worker-1.workers:8080")))
                .isEqualTo("http://na-east-control-plane:30088/api/v1/workers/payments-na-east-worker-1");
        assertThat(router.dial(new WorkerRef("na-west", "w-1", "http://w-1:8080")))
                .isEqualTo("http://w-1:8080");
        assertThat(router.dial(new WorkerRef("elsewhere", "w-2", "http://w-2:8080")))
                .isEqualTo("http://w-2:8080");
        assertThat(router.dial(new WorkerRef(null, "w-3", "http://w-3:8080")))
                .isEqualTo("http://w-3:8080");
        // An operator-declared worker in a routed region has a reachable address of its own.
        assertThat(router.dial(new WorkerRef("na-east", "vm-7", "http://10.20.30.7:8080")))
                .isEqualTo("http://10.20.30.7:8080");
        assertThat(router.dial(new WorkerRef("na-east", "w-9", "http://w-9.workers.jmeter-cloud.svc.cluster.local:8080")))
                .isEqualTo("http://na-east-control-plane:30088/api/v1/workers/w-9");
    }
}
