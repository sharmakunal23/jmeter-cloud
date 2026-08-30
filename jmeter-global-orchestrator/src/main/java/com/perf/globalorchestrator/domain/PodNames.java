package com.perf.globalorchestrator.domain;

import java.util.regex.Pattern;

/**
 * DNS-1123 label check for worker pod names — the same rule the regional
 * enforces ({@code PodNames} there), applied at the hub's edge so an invalid
 * name is a clean 400 here, never a relay URL.
 */
public final class PodNames {

    private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$");

    private PodNames() { }

    public static boolean isValid(String podName) {
        return podName != null && DNS_LABEL.matcher(podName).matches();
    }
}
