package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.config.Backend;
import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.storage.HttpResultSink;
import com.perf.orchestrator.storage.ResultSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;

/**
 * Picks the right {@link ResultSink} for the configured {@link Backend} and
 * the JAR's actual classpath.
 *
 * <p>The decision is two-step:
 * <ol>
 *   <li>Config — {@code RESULT_SINK} env var (validated in {@link OrchestratorConfig}).
 *       {@code S3} is rejected at config-load time so we only need to
 *       handle {@code HTTP_UPLOAD} and {@code DOCUMENT_SERVICE} here.</li>
 *   <li>Class presence — {@code DocumentServiceResultSink} is only on the
 *       classpath when the JAR was built with {@code -Pstorage-docservice}.
 *       We resolve it via {@code Class.forName} so the default JAR has no
 *       compile-time reference to the optional class.</li>
 * </ol>
 *
 * <p>If config asks for {@code DOCUMENT_SERVICE} but the class isn't on the
 * classpath, we fail loud at startup rather than silently falling back —
 * a misbuilt deployment shouldn't pretend to be uploading and quietly
 * drop every JTL.
 */
public final class ResultSinks {

    private static final Logger LOG = LoggerFactory.getLogger(ResultSinks.class);

    private static final String DOC_SERVICE_CLASS =
            "com.perf.orchestrator.storage.DocumentServiceResultSink";

    private ResultSinks() {}

    public static ResultSink forConfig(OrchestratorConfig config) {
        return switch (config.getResultSink()) {
            case HTTP_UPLOAD -> {
                LOG.info("RESULT_SINK=HTTP_UPLOAD — using HttpResultSink (JTL stays local)");
                yield new HttpResultSink();
            }
            case DOCUMENT_SERVICE -> {
                LOG.info("RESULT_SINK=DOCUMENT_SERVICE — instantiating DocumentServiceResultSink");
                yield instantiateDocServiceSink(config);
            }
            // S3 is rejected at config-load time; the switch is exhaustive
            // because the enum has no other values, and the validator means
            // this branch is unreachable. Throw to be safe.
            case S3 -> throw new IllegalStateException(
                    "RESULT_SINK=S3 should have been rejected by OrchestratorConfig validation");
        };
    }

    private static ResultSink instantiateDocServiceSink(OrchestratorConfig config) {
        Class<?> clazz;
        try {
            clazz = Class.forName(DOC_SERVICE_CLASS);
        } catch (ClassNotFoundException cnfe) {
            throw new IllegalStateException(
                    "RESULT_SINK=DOCUMENT_SERVICE requires the JAR to be built with " +
                    "-Pstorage-docservice — the class " + DOC_SERVICE_CLASS + " is not on the classpath. " +
                    "Either rebuild with the profile or switch RESULT_SINK to HTTP_UPLOAD.");
        }
        try {
            Constructor<?> c = clazz.getConstructor(OrchestratorConfig.class);
            return (ResultSink) c.newInstance(config);
        } catch (ReflectiveOperationException roe) {
            throw new IllegalStateException(
                    "Could not instantiate " + DOC_SERVICE_CLASS, roe);
        }
    }
}
