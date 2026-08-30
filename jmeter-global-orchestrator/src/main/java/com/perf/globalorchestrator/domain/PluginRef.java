package com.perf.globalorchestrator.domain;

/**
 * A run's launch-time reference to one registered plugin — the element of the
 * {@code ORCH_RUN.PLUGINS} JSON array and of {@link Run#plugins()}.
 *
 * <p>Deliberately a snapshot, not a foreign key: a registry delete must never
 * break a historical run or a scale-up joiner fanning out from the stored row.
 */
public record PluginRef(
        String pluginId,
        String name,
        String version,
        String blobId,
        String fileName) {

    public static PluginRef of(Plugin p) {
        return new PluginRef(p.pluginId(), p.name(), p.version(), p.blobId(), p.fileName());
    }
}
