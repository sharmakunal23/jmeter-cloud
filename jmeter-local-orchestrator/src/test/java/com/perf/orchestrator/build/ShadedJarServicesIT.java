package com.perf.orchestrator.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Inspects the shaded fat JAR on disk and asserts the things that are
 * trivial to break in {@code pom.xml} but only surface as runtime
 * regressions otherwise:
 * <ul>
 *   <li>{@code META-INF/services/} entries for SLF4J + Kafka serializer
 *       discovery are preserved (the {@code ServicesResourceTransformer}
 *       merges them; an accidental edit to the shade config would silently
 *       break log binding or producer init).</li>
 *   <li>The shaded {@code Main-Class} matches what the operator expects.</li>
 *   <li>The fat JAR stays under its documented budget on the default profile.</li>
 * </ul>
 *
 * <p>Tagged as {@code *IT.java} so it runs under {@code mvn verify} after
 * {@code mvn package} has produced the JAR. The test is a no-op when the
 * JAR is absent (e.g., in IDE / `mvn test` runs).
 */
@DisplayName("Shaded JAR — META-INF/services + Main-Class + size budget")
class ShadedJarServicesIT {

    private static final long DEFAULT_PROFILE_MAX_BYTES = 60L * 1024L * 1024L;
    private static final String EXPECTED_MAIN_CLASS = "com.perf.orchestrator.OrchestratorMain";

    /**
     * Service providers that <em>must</em> survive shading. Each path is the
     * service-file the corresponding class loader walks at runtime; a
     * missing entry here means the production JAR will silently fall back
     * to a NOP impl (SLF4J) or refuse to construct a producer (Kafka).
     */
    private static final List<String> REQUIRED_SERVICE_FILES = List.of(
            "META-INF/services/org.slf4j.spi.SLF4JServiceProvider",
            "META-INF/services/com.fasterxml.jackson.core.JsonFactory",
            "META-INF/services/com.fasterxml.jackson.core.ObjectCodec",
            "META-INF/services/org.apache.kafka.common.config.provider.ConfigProvider"
    );

    /**
     * Spring Boot 3.x auto-configuration import files. The shade plugin's
     * default behaviour is to KEEP-LAST-JAR for any duplicate file, which
     * leaves the shaded fat JAR with only one library's auto-config lines
     * — silently breaking dozens of @AutoConfiguration classes
     * (CompositeMeterRegistryAutoConfiguration, KafkaAutoConfiguration,
     * WebMvcAutoConfiguration, …) at runtime. Symptom is a cryptic
     * NoSuchBeanDefinitionException on a bean that should have been
     * auto-created (typically MeterRegistry).
     *
     * <p>The pom.xml shade plugin config has explicit AppendingTransformer
     * entries for each path here; this test asserts those transformers are
     * still wired so a future "simplify the shade config" edit can't
     * regress.
     */
    private static final List<String> REQUIRED_SPRING_AUTOCONFIG_FILES = List.of(
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
            "META-INF/spring.factories"
    );

    @Nested
    @DisplayName("default-profile shaded JAR")
    class DefaultProfile {

        @Test
        @DisplayName("preserves the service files SLF4J + Jackson + Kafka discover at runtime")
        void preserves_required_service_files() throws IOException {
            Path jar = locateShadedJar();
            try (JarFile jf = new JarFile(jar.toFile())) {
                List<String> missing = new ArrayList<>();
                for (String required : REQUIRED_SERVICE_FILES) {
                    ZipEntry entry = jf.getEntry(required);
                    if (entry == null || entry.getSize() == 0) missing.add(required);
                }
                assertThat(missing)
                        .as("ServicesResourceTransformer must merge every required META-INF/services file " +
                            "into the shaded JAR — missing entries silently break the corresponding API at runtime")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("preserves Spring Boot auto-configuration imports — the merged file must hold every library's @AutoConfiguration entries")
        void preserves_spring_autoconfig_imports() throws IOException {
            Path jar = locateShadedJar();
            try (JarFile jf = new JarFile(jar.toFile())) {
                List<String> missing = new ArrayList<>();
                for (String required : REQUIRED_SPRING_AUTOCONFIG_FILES) {
                    ZipEntry entry = jf.getEntry(required);
                    if (entry == null || entry.getSize() == 0) missing.add(required);
                }
                assertThat(missing)
                        .as("AppendingTransformer entries in the shade plugin must merge every " +
                            "Spring Boot 3.x auto-config imports file. Missing entries cause " +
                            "NoSuchBeanDefinitionException on MeterRegistry / KafkaTemplate / etc. " +
                            "at container boot — silent in `mvn test` (unshaded), only visible " +
                            "via `docker compose up` of the fat JAR.")
                        .isEmpty();

                // Sanity-check the imports file is non-trivial: a single library's
                // imports tend to be 10-30 lines; the merged file should be >100.
                ZipEntry imports = jf.getEntry(
                        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
                long bytes = imports.getSize();
                assertThat(bytes)
                        .as("merged AutoConfiguration.imports should be > 1 KB; %d bytes suggests " +
                            "the AppendingTransformer isn't actually appending and only one library's " +
                            "imports file made it into the shaded JAR.", bytes)
                        .isGreaterThan(1024L);
            }
        }

        @Test
        @DisplayName("declares OrchestratorMain as Main-Class — the deferred-rename flip from step 11")
        void main_class_is_orchestrator() throws IOException {
            Path jar = locateShadedJar();
            try (JarFile jf = new JarFile(jar.toFile())) {
                String mainClass = jf.getManifest().getMainAttributes().getValue("Main-Class");
                assertThat(mainClass)
                        .as("Main-Class baked into the shaded manifest")
                        .isEqualTo(EXPECTED_MAIN_CLASS);
            }
        }

        @Test
        @DisplayName("stays under the documented 25 MB default-profile JAR budget")
        void size_within_budget() throws IOException {
            Path jar = locateShadedJar();
            long size = Files.size(jar);

            assertSoftly(softly -> {
                softly.assertThat(size)
                        .as("default-profile fat JAR must remain ≤ 60 MB (hard constraint, " +
                            "relaxed from 25 MB when the project moved to Spring Boot 3 in Step 4)")
                        .isLessThanOrEqualTo(DEFAULT_PROFILE_MAX_BYTES);
                softly.assertThat(size)
                        .as("a near-empty JAR almost certainly means the wrong file was located")
                        .isGreaterThan(5L * 1024L * 1024L);
            });
        }
    }

    // -----------------------------------------------------------------------
    // Helper — find the shaded JAR in target/, ignoring the original-* twin
    // -----------------------------------------------------------------------

    private static Path locateShadedJar() throws IOException {
        Path target = Paths.get("target");
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(target, "jmeter-local-orchestrator-*.jar")) {
            List<Path> matches = new ArrayList<>();
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.startsWith("original-")) continue;
                matches.add(p);
            }
            if (matches.isEmpty()) {
                throw new IOException("No shaded JAR found in target/ (run mvn package first)");
            }
            // Pick the most-recently modified, in case multiple builds linger.
            matches.sort(Comparator.comparingLong(p -> {
                try { return Files.getLastModifiedTime(p).toMillis(); }
                catch (IOException io) { return 0L; }
            }));
            return matches.get(matches.size() - 1);
        }
    }
}
