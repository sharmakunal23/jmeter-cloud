package com.perf.globalorchestrator.region;

/**
 * A region cannot serve a provisioning or relay call: it has no regional
 * orchestrator URL, or the one it has did not answer. Mapped to
 * {@code 503 REGION_UNREACHABLE}.
 */
public class RegionUnavailableException extends RuntimeException {

    private final String region;

    public RegionUnavailableException(String region, String message) {
        super(message);
        this.region = region;
    }

    public RegionUnavailableException(String region, String message, Throwable cause) {
        super(message, cause);
        this.region = region;
    }

    public String region() {
        return region;
    }
}
