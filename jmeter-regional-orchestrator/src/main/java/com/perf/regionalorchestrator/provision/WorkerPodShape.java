package com.perf.regionalorchestrator.provision;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything about a worker Pod's spec that a hosting platform dictates and
 * a local cluster does not — read from {@code PODPROVISIONER_*} env
 * (PRIVATE-CLOUD-ALIGNMENT Track 8). {@link #DEFAULTS} is the kind / compose
 * shape: cpu + memory resources, no SA, no pull secret, the image's user.
 *
 * @param cpuMemoryResources  {@code false} under a hard-zero namespace quota
 *                            ({@code requests.cpu: 0}) — omit cpu/memory
 *                            entirely and bound the JVMs with flags instead
 * @param workerCpuLimit      a CPU limit (e.g. {@code "2"}); required where the
 *                            namespace quota counts {@code limits.cpu}. Null =
 *                            request only (no cfs throttling — the local default)
 * @param ephemeralStorage    request == limit (a {@code maxLimitRequestRatio: 1}
 *                            LimitRange); null = the LimitRange's default
 * @param serviceAccountName  null = the namespace's default SA. Workers never
 *                            call the cluster API, so the token is never mounted
 * @param imagePullSecret     pod-level {@code imagePullSecrets} for a private
 *                            worker image; null = none
 * @param runAsUser           pod securityContext ({@code runAsNonRoot} follows);
 *                            null = the image's user
 * @param runAsGroup          see {@code runAsUser}
 * @param fsGroup             see {@code runAsUser}
 * @param extraLabels         labels added to every worker Pod (e.g. what a
 *                            platform NetworkPolicy selects on)
 * @param workerJavaOpts      stamped as {@code JAVA_OPTS} — the orchestrator
 *                            JVM's flags (the image's default when null)
 */
public record WorkerPodShape(
        boolean cpuMemoryResources,
        String workerCpuLimit,
        String ephemeralStorage,
        String serviceAccountName,
        String imagePullSecret,
        Long runAsUser,
        Long runAsGroup,
        Long fsGroup,
        Map<String, String> extraLabels,
        String workerJavaOpts) {

    public static final WorkerPodShape DEFAULTS =
            new WorkerPodShape(true, null, null, null, null, null, null, null, Map.of(), null);

    public WorkerPodShape {
        extraLabels = extraLabels == null ? Map.of() : Map.copyOf(extraLabels);
    }

    public boolean hasSecurityContext() {
        return runAsUser != null || runAsGroup != null || fsGroup != null;
    }

    /** {@code k=v,k2=v2} → map; blank → empty. A pair without {@code =} is rejected. */
    public static Map<String, String> parseLabels(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String pair : raw.split(",")) {
            String p = pair.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            if (eq <= 0 || eq == p.length() - 1) {
                throw new IllegalArgumentException("PODPROVISIONER_EXTRA_LABELS: expected key=value, got '" + p + "'");
            }
            out.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return out;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
