package com.perf.globalorchestrator.region;

import com.perf.globalorchestrator.client.RegionalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The add-cluster dry run (CLUSTER-CAPACITY): before a region row is written,
 * prove its regional orchestrator is reachable, agrees on the region id,
 * carries a worker image, and can actually create worker Pods (the regional's
 * own {@code GET /api/v1/provisioningCheck} — RBAC + quota headroom). Every
 * check lands in the returned list so the operator sees the whole checklist;
 * a failure throws {@link ClusterValidationException} carrying the list and
 * the first failing check's stable code.
 */
@Service
public class RegionValidationService {

    private static final Logger LOG = LoggerFactory.getLogger(RegionValidationService.class);

    /**
     * Region ids are DNS-1123-shaped and capped at 20 chars so a dynamic pod
     * name {@code {groupId(≤30)}-{region}-worker-{n}} stays a valid ≤63-char
     * DNS-1123 label.
     */
    public static final Pattern REGION_ID = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,18}[a-z0-9])?$");

    /** One validation check; {@code code} is the stable error code a failure reports. */
    public record ClusterCheck(String name, boolean ok, String detail, String code) {}

    private final RegionalClient client;

    public RegionValidationService(RegionalClient client) {
        this.client = client;
    }

    /**
     * Runs the chain and returns every check (all {@code ok}) — or throws
     * {@link ClusterValidationException} with the full list once a check fails.
     * The chain stops where continuing proves nothing (an unreachable endpoint
     * has no image to inspect).
     */
    public List<ClusterCheck> validate(String region, String regionalUrl) {
        List<ClusterCheck> checks = new ArrayList<>();

        String urlProblem = urlProblem(regionalUrl);
        if (urlProblem != null) {
            checks.add(new ClusterCheck("endpointReachable", false, urlProblem, "INVALID_CLUSTER_URL"));
            throw fail(region, checks);
        }

        RegionCapabilities caps;
        try {
            caps = client.capabilities(regionalUrl);
            checks.add(new ClusterCheck("endpointReachable", true,
                    "regional orchestrator answered at " + regionalUrl
                            + " (version " + caps.version() + ")", "CLUSTER_UNREACHABLE"));
        } catch (RuntimeException e) {
            checks.add(new ClusterCheck("endpointReachable", false,
                    "no regional orchestrator answered at " + regionalUrl + "/api/v1/capabilities — "
                            + cause(e), "CLUSTER_UNREACHABLE"));
            throw fail(region, checks);
        }

        boolean regionMatches = region.equals(caps.region());
        checks.add(new ClusterCheck("regionMatches", regionMatches,
                regionMatches
                        ? "the regional reports region '" + caps.region() + "'"
                        : "this cluster's regional reports region '" + caps.region()
                                + "', not '" + region + "' — the id here must equal the regional's REGION env",
                "REGION_MISMATCH"));

        RegionalClient.ProvisioningCheckResult dryRun = null;
        try {
            dryRun = client.provisioningCheck(regionalUrl);
            for (RegionalClient.ProvisioningCheck c : dryRun.checks()) {
                checks.add(new ClusterCheck(c.name(), c.ok(), c.detail(), codeFor(c.name())));
            }
        } catch (RuntimeException e) {
            checks.add(new ClusterCheck("provisioningCheck", false,
                    "the regional's provisioning dry run failed: " + cause(e), "CLUSTER_UNREACHABLE"));
        }

        if (checks.stream().anyMatch(c -> !c.ok())) {
            throw fail(region, checks);
        }
        LOG.info("cluster {} validated at {} (image={}, workersFree={})",
                region, regionalUrl, dryRun.image(), caps.workersFree());
        return checks;
    }

    private static String codeFor(String regionalCheckName) {
        return switch (regionalCheckName) {
            case "imageConfigured" -> "NO_WORKER_IMAGE";
            case "quotaHeadroom"   -> "QUOTA_EXHAUSTED";
            default                -> "RBAC_DENIED";   // rbacPods / rbacPodsLog / rbacResourceQuotas
        };
    }

    private static String urlProblem(String url) {
        if (url == null || url.isBlank()) return "regionalUrl is required";
        try {
            URI u = URI.create(url.trim());
            if (u.getScheme() == null || !(u.getScheme().equals("http") || u.getScheme().equals("https"))) {
                return "regionalUrl must be http(s): " + url;
            }
            if (u.getHost() == null) {
                return "regionalUrl has no host: " + url;
            }
        } catch (IllegalArgumentException e) {
            return "regionalUrl is not a valid URL: " + url;
        }
        return null;
    }

    private static ClusterValidationException fail(String region, List<ClusterCheck> checks) {
        ClusterCheck first = checks.stream().filter(c -> !c.ok()).findFirst().orElseThrow();
        return new ClusterValidationException(region, first.code(), first.detail(), List.copyOf(checks));
    }

    private static String cause(RuntimeException e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    /** Carries the failing code, a human sentence and the whole checklist for the UI. */
    public static final class ClusterValidationException extends RuntimeException {
        public final String region;
        public final String code;
        public final List<ClusterCheck> checks;

        public ClusterValidationException(String region, String code, String message, List<ClusterCheck> checks) {
            super(message);
            this.region = region;
            this.code = code;
            this.checks = checks;
        }
    }
}
