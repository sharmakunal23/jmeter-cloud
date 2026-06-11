package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.config.Backend;
import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.storage.ArtifactSource;
import com.perf.orchestrator.storage.HttpArtifactSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;

/**
 * Picks the right {@link ArtifactSource} for the configured {@link Backend}
 * and the JAR's actual classpath — analog of {@link ResultSinks}.
 *
 * <p>{@code S3ArtifactSource} and {@code DocumentServiceArtifactSource}
 * are profile-gated: built only with {@code -Pstorage-s3} /
 * {@code -Pstorage-docservice}. The factory uses {@link Class#forName}
 * so the default JAR has no compile-time reference to either class.
 *
 * <p>If config asks for an optional backend whose class isn't on the
 * classpath, we fail loud at startup rather than silently falling back —
 * a misbuilt deployment shouldn't pretend to be pulling from S3 and
 * quietly run with stale local artifacts.
 */
public final class ArtifactSources {

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactSources.class);

    private static final String S3_CLASS =
            "com.perf.orchestrator.storage.S3ArtifactSource";
    private static final String DOC_SERVICE_CLASS =
            "com.perf.orchestrator.storage.DocumentServiceArtifactSource";

    private ArtifactSources() {}

    public static ArtifactSource forConfig(OrchestratorConfig config) {
        return switch (config.getArtifactSource()) {
            case HTTP_UPLOAD -> {
                LOG.info("ARTIFACT_SOURCE=HTTP_UPLOAD — using HttpArtifactSource (files arrive via REST upload)");
                yield new HttpArtifactSource();
            }
            case S3 -> {
                LOG.info("ARTIFACT_SOURCE=S3 — instantiating S3ArtifactSource");
                yield instantiate(S3_CLASS, config,
                        "Either rebuild with -Pstorage-s3 or switch ARTIFACT_SOURCE to HTTP_UPLOAD.");
            }
            case DOCUMENT_SERVICE -> {
                LOG.info("ARTIFACT_SOURCE=DOCUMENT_SERVICE — instantiating DocumentServiceArtifactSource");
                yield instantiate(DOC_SERVICE_CLASS, config,
                        "Either rebuild with -Pstorage-docservice or switch ARTIFACT_SOURCE to HTTP_UPLOAD.");
            }
        };
    }

    private static ArtifactSource instantiate(String fqcn, OrchestratorConfig config, String hint) {
        Class<?> clazz;
        try {
            clazz = Class.forName(fqcn);
        } catch (ClassNotFoundException cnfe) {
            throw new IllegalStateException(
                    "Configured artifact source requires the class " + fqcn +
                    " to be on the classpath, but it isn't. " + hint);
        }
        try {
            Constructor<?> c = clazz.getConstructor(OrchestratorConfig.class);
            return (ArtifactSource) c.newInstance(config);
        } catch (ReflectiveOperationException roe) {
            throw new IllegalStateException("Could not instantiate " + fqcn, roe);
        }
    }
}
