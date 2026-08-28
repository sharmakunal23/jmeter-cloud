package com.perf.globalorchestrator.client;

import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.RunFleetMember;

/**
 * Everything {@link LocalOrchestratorClient} needs to dial a worker: its
 * region decides the route (through the region's relay, or direct), and
 * {@code podName} + {@code baseUrl} are the two possible targets.
 */
public record WorkerRef(String region, String podName, String baseUrl) {

    public static WorkerRef of(Pod pod) {
        return new WorkerRef(pod.region(), pod.podId(), pod.baseUrl());
    }

    public static WorkerRef of(RunFleetMember member) {
        return new WorkerRef(member.region(), member.workerId(), member.podBaseUrl());
    }
}
