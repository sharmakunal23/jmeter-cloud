package com.perf.globalorchestrator.region;

/** A regional orchestrator answered a provisioning call with an error body. Mapped to {@code 502 REGIONAL_CALL_FAILED}. */
public class RegionalCallException extends RuntimeException {

    private final int status;
    private final String code;

    public RegionalCallException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
