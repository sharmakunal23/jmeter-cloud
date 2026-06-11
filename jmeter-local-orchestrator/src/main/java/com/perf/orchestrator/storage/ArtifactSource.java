package com.perf.orchestrator.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Backend-neutral source of test artifacts.
 *
 * <p>Implementations: {@link HttpArtifactSource} (default; files arrive via
 * {@code POST /testPlan} / {@code POST /dataFiles} and are served from
 * local disk by the controllers, so this interface returns
 * {@link Optional#empty()}); {@code S3ArtifactSource} (Maven profile
 * {@code -Pstorage-s3}, added in step 10); {@code DocumentServiceArtifactSource}
 * (Maven profile {@code -Pstorage-docservice}, added in step 9/10).
 *
 * <p>Returning {@link Optional#empty()} means "no artifact configured for
 * this run" and is a normal control-flow outcome, not an error. Throwing
 * {@link IOException} is reserved for genuine I/O failures (network down,
 * 5xx from the backend, etc.).
 *
 * <p>The caller owns the returned {@link InputStream} and must close it.
 */
@FunctionalInterface
public interface ArtifactSource {

    /** Marker passed to {@link #fetch(String, FetchSpec)} for the test plan slot. */
    String KIND_TEST_PLAN = "TEST_PLAN";

    /** Marker passed to {@link #fetch(String, FetchSpec)} for the dataFiles slot. */
    String KIND_DATA_FILES = "DATA_FILES";

    /**
     * Fetches the artifact identified by {@code kind} for the given run.
     *
     * @param kind one of {@link #KIND_TEST_PLAN} or {@link #KIND_DATA_FILES}
     * @param spec opaque parameters (run ID + backend-specific keys)
     * @return the artifact stream, or empty if none is configured for this kind
     * @throws IOException on transport / backend failure
     */
    Optional<InputStream> fetch(String kind, FetchSpec spec) throws IOException;
}
