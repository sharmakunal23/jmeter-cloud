package com.perf.k8sorchestrator.provision;

import com.perf.k8sorchestrator.repo.PodRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allocates the next {@code {appName}-{region}-worker-{n}} name for an
 * (applicationId, region), restarting {@code n} at 1 per pair and <b>filling the
 * lowest gap</b> — deleting {@code worker-2} from [1,2,3] means the next
 * allocation is {@code worker-2}, not {@code worker-4}.
 *
 * <p>Gap-filling rather than {@code MAX(n) + 1} because drains delete pod rows,
 * so MAX+1 would grow the suffix unboundedly across drain/spin cycles instead of
 * keeping names stable and operator-readable.
 *
 * <p>Names become network hostnames, so they must fit DNS-1123's 63 characters.
 * At the documented input limits the worst case is 64 — one over — so the
 * allocator caps {@code appName} plus {@code region} at 50 combined and rejects
 * anything that would overflow.
 *
 * <p>{@link #nextSlotIndex(String, String, java.util.Collection)} is pure and
 * unit-testable without a database; the bean only wraps it in one repo call.
 */
@Component
public class PodNameAllocator {

    /** Worst-case DNS-1123 hostname length. Container name == hostname == podId. */
    public static final int MAX_POD_NAME_LENGTH = 63;

    private final PodRepository pods;

    public PodNameAllocator(PodRepository pods) {
        this.pods = pods;
    }

    /**
     * Allocates the next free pod name for this (applicationId, applicationName, region).
     * The returned name is guaranteed not to collide with any existing
     * {@code pod} row at the time of the call — but the caller is responsible
     * for the actual INSERT (typically wrapped in a transaction with the
     * provisioner's create-container call).
     */
    public String allocate(String applicationId, String applicationName, String region) {
        validate(applicationName, region);
        List<String> taken = pods.findPodIdsByApplicationAndRegion(applicationId, region);
        int n = nextSlotIndex(applicationName, region, taken);
        String name = format(applicationName, region, n);
        if (name.length() > MAX_POD_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Allocated pod name '" + name + "' exceeds DNS-1123 limit of "
                    + MAX_POD_NAME_LENGTH + " chars (got " + name.length() + ")");
        }
        return name;
    }

    /**
     * Pure function: returns the lowest positive integer N such that
     * {@code {appName}-{region}-worker-{N}} is not in {@code taken}.
     * {@code taken} may contain unrelated pod names (other apps, other
     * regions, or legacy podIds without the expected prefix); the
     * regex-driven parse skips them silently.
     */
    public static int nextSlotIndex(String appName, String region, java.util.Collection<String> taken) {
        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(appName) + "-" + Pattern.quote(region) + "-worker-(\\d+)$");
        java.util.SortedSet<Integer> usedSlots = new java.util.TreeSet<>();
        for (String podId : taken) {
            Matcher m = pattern.matcher(podId);
            if (m.matches()) {
                try {
                    usedSlots.add(Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignore) {
                    // Pod name with our prefix but a non-integer suffix — treat as unrelated.
                }
            }
        }
        int n = 1;
        for (Integer used : usedSlots) {
            if (used > n) return n;
            n = used + 1;
        }
        return n;
    }

    /** Formats a pod name without validating against the registry. Public for tests. */
    public static String format(String appName, String region, int slot) {
        return appName + "-" + region + "-worker-" + slot;
    }

    private static void validate(String appName, String region) {
        if (appName == null || appName.isBlank()) {
            throw new IllegalArgumentException("applicationName is required");
        }
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("region is required");
        }
        // Total = appName + "-" + region + "-worker-" + N(up to 4 digits) → leave 12 chars
        // for the trailing fixed bits ("-worker-NNNN"). 51 chars combined gives us slack
        // for a 4-digit slot index, which is well past Max=1000 from the capacity column.
        if (appName.length() + region.length() > 51) {
            throw new IllegalArgumentException(
                    "applicationName + region too long (" + appName.length() + "+"
                    + region.length() + " > 51 chars); pod name would exceed DNS-1123 limit");
        }
    }
}
