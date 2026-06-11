package com.perf.orchestrator.storage;

import java.nio.file.Path;

/**
 * Default {@link ResultSink} for {@code RESULT_SINK=HTTP_UPLOAD}.
 *
 * <p>"HTTP upload" as a sink is a misnomer — there is no remote system to
 * push to. The JTL stays on local disk and the upstream caller fetches it
 * via {@code GET /api/v1/results/file}. So this sink does no work and
 * returns {@link UploadResult#skipped()}.
 *
 * <p>The orchestrator's lifecycle code (step 9) treats {@code skipped=true}
 * as the normal terminal — {@code uploadState=SKIPPED} in
 * {@code GET /api/v1/test}. This is the correct default until the document
 * service contract is live.
 */
public final class HttpResultSink implements ResultSink {

    @Override
    public UploadResult upload(String application, String runId, String workerId, Path file) {
        return UploadResult.noUpload();
    }
}
