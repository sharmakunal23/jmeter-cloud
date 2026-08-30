package com.perf.regionalorchestrator.provision;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;

import java.util.List;

/**
 * A worker Pod's liveness as the kubelet reports it — the hub's source of
 * truth for LOST, replacing worker heartbeats. {@code dead} is the one flag
 * callers act on: a terminated or failed container, a Pod the scheduler has
 * given up on, or a phase past Running; {@code reason} then carries the
 * kubelet's word for it ({@code OOMKilled}, {@code Error}, {@code Unschedulable},
 * {@code ImagePullBackOff}) with the exit code when there is one.
 */
public record WorkerState(
        String podName,
        String groupId,
        String region,
        String phase,
        boolean ready,
        boolean dead,
        String reason,
        Integer exitCode,
        int restarts,
        String message) {

    public static WorkerState from(Pod pod) {
        String name = pod.getMetadata().getName();
        String group = pod.getMetadata().getLabels() == null ? null
                : pod.getMetadata().getLabels().get(ProvisionerProperties.LABEL_GROUP_ID);
        String region = pod.getMetadata().getLabels() == null ? null
                : pod.getMetadata().getLabels().get(ProvisionerProperties.LABEL_REGION);
        String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
        boolean ready = false;
        String reason = null, message = null;
        Integer exitCode = null;
        int restarts = 0;
        boolean dead = "Failed".equals(phase) || "Succeeded".equals(phase);
        if (pod.getStatus() != null) {
            List<PodCondition> conditions = pod.getStatus().getConditions();
            if (conditions != null) {
                for (PodCondition c : conditions) {
                    if ("Ready".equals(c.getType()) && "True".equals(c.getStatus())) ready = true;
                    if ("PodScheduled".equals(c.getType()) && "False".equals(c.getStatus())
                            && "Unschedulable".equals(c.getReason())) {
                        dead = true;
                        reason = "Unschedulable";
                        message = c.getMessage();
                    }
                }
            }
            List<ContainerStatus> containers = pod.getStatus().getContainerStatuses();
            if (containers != null && !containers.isEmpty()) {
                ContainerStatus cs = containers.get(0);
                restarts = cs.getRestartCount() == null ? 0 : cs.getRestartCount();
                if (cs.getState() != null && cs.getState().getTerminated() != null) {
                    dead = true;
                    reason = cs.getState().getTerminated().getReason();
                    exitCode = cs.getState().getTerminated().getExitCode();
                    message = cs.getState().getTerminated().getMessage();
                } else if (cs.getState() != null && cs.getState().getWaiting() != null) {
                    String waiting = cs.getState().getWaiting().getReason();
                    // ContainerCreating / PodInitializing are normal start-up; the
                    // back-off states mean the kubelet has given up on this image.
                    if (waiting != null && waiting.endsWith("BackOff")) {
                        dead = true;
                        reason = waiting;
                        message = cs.getState().getWaiting().getMessage();
                    }
                    if (cs.getLastState() != null && cs.getLastState().getTerminated() != null) {
                        exitCode = cs.getLastState().getTerminated().getExitCode();
                        if (reason == null) reason = cs.getLastState().getTerminated().getReason();
                    }
                }
            }
        }
        if (dead && reason == null) reason = phase;
        return new WorkerState(name, group, region, phase, ready, dead, reason, exitCode, restarts, message);
    }
}
