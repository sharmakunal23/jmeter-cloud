package com.perf.orchestrator.config;

/**
 * Thrown when the orchestrator cannot start due to missing or invalid
 * environment configuration. Always carries a message that names
 * the offending variable(s) so the operator knows exactly what to fix.
 */
public final class OrchestratorConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OrchestratorConfigException(String message) {
        super(message);
    }
}
