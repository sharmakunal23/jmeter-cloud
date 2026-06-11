package com.perf.orchestrator.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Backend-neutral destination for the post-test JTL.
 *
 * <p>Only invoked when {@code AUTO_UPLOAD_RESULTS=true}. With the default
 * {@code AUTO_UPLOAD_RESULTS=false}, the orchestrator returns
 * {@link UploadResult#skipped()} without ever calling the configured sink —
 * the JTL stays on local disk and is fetched by the caller via
 * {@code GET /api/v1/results/file}.
 *
 * <p>Implementations: {@link HttpResultSink} (default; no-op because the JTL
 * is served from local disk by the controller); {@code DocumentServiceResultSink}
 * (Maven profile {@code -Pstorage-docservice}, added in step 9). S3 is
 * intentionally <b>not</b> implemented as a sink — see
 * {@code docs/ORCHESTRATOR-PLAN.md} "Storage Backends".
 */
@FunctionalInterface
public interface ResultSink {

    /**
     * Uploads the file at {@code file} for the given {@code application},
     * {@code runId} and {@code workerId}, returning a backend-specific
     * identifier the orchestrator can surface to the operator.
     *
     * @param application the run's application name (may be {@code null} for
     *                    an untagged run) — used by the Document Service sink
     *                    to file the result under the right app for the
     *                    download-all-by-run flow
     * @param runId    the test run ID (set on {@code POST /test})
     * @param workerId the worker identity (pod name or thread name)
     * @param file     local path to the (typically gzipped) JTL
     * @return descriptor of the upload outcome; never {@code null}
     * @throws IOException on transport / backend failure
     */
    UploadResult upload(String application, String runId, String workerId, Path file) throws IOException;
}
