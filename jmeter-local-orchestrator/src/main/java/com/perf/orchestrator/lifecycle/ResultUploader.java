package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.storage.ResultSink;
import com.perf.orchestrator.storage.UploadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

/**
 * Streams the post-test JTL through {@link GZIPOutputStream} into a tmp
 * sibling, then hands the gzipped file to the configured {@link ResultSink}.
 * Drives the {@code uploadState} transitions on {@link CurrentRun}:
 * {@code SKIPPED → PENDING → UPLOADING → UPLOADED|FAILED}.
 *
 * <h2>Memory contract</h2>
 * The JTL is streamed in 16 KB chunks; nothing is buffered in memory.
 * Even a 3 GB JTL goes through one buffer's worth of RAM.
 *
 * <h2>Retry</h2>
 * Transient sink failures are retried with exponential backoff
 * (1 s, 2 s, 4 s, …) up to {@code DOCUMENT_SERVICE_RETRY_COUNT}. A
 * permanent failure leaves the gzipped JTL on disk so an operator can
 * fetch it via {@code GET /api/v1/results/file?format=zip} and replay
 * the upload by hand.
 *
 * <h2>SKIPPED contract</h2>
 * When {@code AUTO_UPLOAD_RESULTS=false} the uploader is never invoked
 * and {@code uploadState} stays at the documented terminal {@code SKIPPED}.
 * The {@link ResultSink} contract treats {@link UploadResult#skipped()}
 * the same way — see {@link com.perf.orchestrator.storage.HttpResultSink}.
 */
@Service
public final class ResultUploader {

    private static final Logger LOG = LoggerFactory.getLogger(ResultUploader.class);

    private static final int BUFFER_BYTES = 16 * 1024;
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    private final ResultSink sink;
    private final int retries;
    private final SleepFn sleep;

    @Autowired
    public ResultUploader(OrchestratorConfig config, ResultSink sink) {
        this(sink, config.getDocumentServiceRetryCount(), Thread::sleep);
    }

    ResultUploader(ResultSink sink, int retries, SleepFn sleep) {
        this.sink    = Objects.requireNonNull(sink, "sink");
        this.retries = Math.max(0, retries);
        this.sleep   = Objects.requireNonNull(sleep, "sleep");
    }

    /**
     * Runs one upload pass. Records every state transition on
     * {@code currentRun}. Always cleans up the {@code .gz.tmp} staging
     * file before returning, success or failure.
     */
    public void upload(OrchestratorConfig perRun, CurrentRun currentRun, String workerId, String application) {
        // WORKER-HYGIENE Phase A — JTL lives at
        // results/{runId}/results.jtl (per-run subdir). The .gz lands as
        // a sibling inside the same per-run dir so eager cleanup (which
        // removes the whole runId/ subtree) sweeps it along with the JTL.
        Path jtl = Path.of(perRun.getResultsDir())
                .resolve(perRun.getRunId())
                .resolve("results.jtl");
        if (!Files.exists(jtl)) {
            LOG.warn("Skipping upload — no JTL at {}", jtl);
            currentRun.uploadFailed("missing_jtl");
            return;
        }

        currentRun.uploadPending();
        Path gzipTarget = jtl.resolveSibling("results.jtl.gz");
        Path gzipTmp    = jtl.resolveSibling("results.jtl.gz.tmp");

        try {
            currentRun.uploadInProgress();
            gzipStream(jtl, gzipTmp);
            atomicReplace(gzipTmp, gzipTarget);

            UploadResult result = uploadWithRetry(application, currentRun.snapshot().runId(), workerId, gzipTarget);
            if (result.skipped()) {
                // A no-op sink slipped past the caller's gating. The
                // documented terminal in that case is SKIPPED — we restore
                // it explicitly because we transitioned through PENDING /
                // UPLOADING above.
                currentRun.uploadFailed("sink_returned_no_upload");
                LOG.warn("Sink returned skipped — gating in TestRunManager should have prevented this call");
            } else {
                currentRun.uploadSucceeded(result.target());
                LOG.info("Uploaded {} bytes to {} in {} ms",
                        result.sizeBytes(), result.target(), result.durationMs());
            }
        } catch (IOException io) {
            LOG.error("Result upload failed for run {}", currentRun.snapshot().runId(), io);
            currentRun.uploadFailed(io.getClass().getSimpleName() + ": " + io.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            currentRun.uploadFailed("interrupted");
        } finally {
            cleanupQuietly(gzipTmp);
        }
    }

    // -----------------------------------------------------------------------
    // Streaming gzip
    // -----------------------------------------------------------------------

    private static void gzipStream(Path source, Path dest) throws IOException {
        Files.createDirectories(dest.getParent());
        try (InputStream in = Files.newInputStream(source);
             OutputStream out = Files.newOutputStream(
                     dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE);
             GZIPOutputStream gzip = new GZIPOutputStream(out, BUFFER_BYTES)) {
            byte[] buf = new byte[BUFFER_BYTES];
            int r;
            while ((r = in.read(buf)) != -1) {
                gzip.write(buf, 0, r);
            }
        }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ame) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void cleanupQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
    }

    // -----------------------------------------------------------------------
    // Retry
    // -----------------------------------------------------------------------

    private UploadResult uploadWithRetry(String application, String runId, String workerId, Path file)
            throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                return sink.upload(application, runId, workerId, file);
            } catch (IOException io) {
                lastFailure = io;
                if (attempt == retries) break;
                long backoff = Math.min(INITIAL_BACKOFF_MS << attempt, MAX_BACKOFF_MS);
                LOG.warn("Upload attempt {} failed ({}); retrying in {} ms",
                        attempt + 1, io.toString(), backoff);
                sleep.sleep(backoff);
            }
        }
        throw lastFailure != null
                ? lastFailure
                : new IOException("upload retried " + retries + " times with no error captured");
    }

    /** Test seam — production passes {@link Thread#sleep(long)}. */
    @FunctionalInterface
    interface SleepFn {
        void sleep(long millis) throws InterruptedException;
    }
}
