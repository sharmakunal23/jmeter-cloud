package com.perf.orchestrator.storage;

import java.io.InputStream;
import java.util.Optional;

/**
 * Default {@link ArtifactSource} for {@code ARTIFACT_SOURCE=HTTP_UPLOAD}.
 *
 * <p>With HTTP upload mode, artifacts are written to local disk by the
 * {@code TestPlanController} / {@code DataFilesController} (added in step 6).
 * The orchestrator's run loop reads them straight off disk, so this source
 * has no remote system to consult and always returns {@link Optional#empty()}.
 *
 * <p>The interface is still wired in so step 7's {@code TestRunManager} can
 * uniformly call {@link #fetch} regardless of which backend is configured —
 * empty means "use the locally-staged file".
 */
public final class HttpArtifactSource implements ArtifactSource {

    @Override
    public Optional<InputStream> fetch(String kind, FetchSpec spec) {
        return Optional.empty();
    }
}
