package com.perf.orchestrator.metrics;

/**
 * Hand-rolled Prometheus text-exposition rendering. Writes ~7 series, so
 * pulling {@code simpleclient_*} (~1 MB) would cost more than the entire
 * orchestrator's worth of feature code we still have left.
 *
 * <p>Output conforms to the
 * <a href="https://prometheus.io/docs/instrumenting/exposition_formats/">Prometheus
 * 0.0.4 text format</a>: each metric has a {@code # HELP} line, a
 * {@code # TYPE} line, and one or more sample lines. Labels are not
 * needed today (one orchestrator per pod; the scrape target IS the
 * worker identity), so every series is unlabelled.
 *
 * <p>Stateless — every call rebuilds the body from the supplied
 * {@link OrchestratorCounters}.
 */
public final class PrometheusExporter {

    public static final String CONTENT_TYPE = "text/plain; version=0.0.4";

    private PrometheusExporter() {}

    public static String render(OrchestratorCounters c) {
        StringBuilder out = new StringBuilder(512);

        line(out, "orchestrator_rows_parsed_total",
                "counter", "Rows parsed from the JTL file since process start.",
                c.rowsParsedTotal());
        line(out, "orchestrator_windows_published_total",
                "counter", "1-second windows successfully published to Kafka.",
                c.windowsPublishedTotal());
        line(out, "orchestrator_kafka_send_errors_total",
                "counter", "Kafka producer delivery failures since process start.",
                c.kafkaSendErrorsTotal());
        line(out, "orchestrator_kafka_last_ack_epoch_ms",
                "gauge", "Wall-clock epoch ms of the most recent Kafka ack (0 if none).",
                c.kafkaLastAckEpochMs());
        line(out, "orchestrator_upload_inflight_bytes",
                "gauge", "Bytes of data-file upload currently being staged on disk.",
                c.uploadInflightBytes());
        line(out, "orchestrator_disk_free_bytes",
                "gauge", "Free bytes on the BASE_DIR filesystem.",
                c.diskFreeBytes());
        line(out, "orchestrator_offset_save_failures_total",
                "counter", "JTL byte-offset persistence failures since process start. Each failure means up to one flush interval of rows may be re-processed (and de-duplicated by Kafka) on the next restart.",
                c.offsetSaveFailuresTotal());

        return out.toString();
    }

    private static void line(StringBuilder out, String name, String type, String help, long value) {
        out.append("# HELP ").append(name).append(' ').append(escape(help)).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        out.append(name).append(' ').append(value).append('\n');
    }

    /** Prometheus help text uses {@code \\}, {@code \n}, {@code \"} escapes. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n");
    }
}
