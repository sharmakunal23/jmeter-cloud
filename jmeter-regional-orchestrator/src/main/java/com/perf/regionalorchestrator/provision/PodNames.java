package com.perf.regionalorchestrator.provision;

import java.util.regex.Pattern;

/**
 * The only pod names this service will create or relay to: a DNS-1123 label
 * ({@code [a-z0-9-]}, 63 chars, no leading/trailing hyphen). Anything else is
 * rejected before it can become a Pod name or a relay target host.
 */
public final class PodNames {

    private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$");

    private PodNames() {}

    public static boolean isValid(String podName) {
        return podName != null && DNS_LABEL.matcher(podName).matches();
    }
}
