package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.storage.ArtifactSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The plugin cache is content-addressed (a cached blob is never re-fetched),
 * bundles extract flat jars only, and the cache stays bounded without ever
 * evicting the current request's set.
 */
class PluginStagerTest {

    private static final String U1 = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final String U2 = "01BX5ZZKBKACTAV9WEVGEMMVRZ";
    private static final String U3 = "01BX5ZZKBKACTAV9WEVGEMMVS0";

    @TempDir
    Path base;

    private OrchestratorConfig config(Map<String, String> extra) {
        Map<String, String> env = new HashMap<>(Map.of(
                "POD_NAME", "w0", "TEST_REGION", "r", "RUN_ID", "boot",
                "JTL_PATH", "/r/results.jtl", "SENTINEL_PATH", "/r/.done"));
        env.put("BASE_DIR", base.toString());
        env.putAll(extra);
        return OrchestratorConfig.from(env);
    }

    private static final class CountingSource implements ArtifactSource {
        final AtomicInteger fetches = new AtomicInteger();
        final byte[] bytes;
        CountingSource(byte[] bytes) { this.bytes = bytes; }
        @Override
        public Optional<InputStream> fetch(String kind, com.perf.orchestrator.storage.FetchSpec spec) {
            fetches.incrementAndGet();
            return bytes == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(bytes));
        }
    }

    private static byte[] zipOf(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    @DisplayName("a bundle entry with ';' is rejected — it would split -Jsearch_paths")
    void semicolonEntryRejected() throws IOException {
        byte[] zip = zipOf(Map.of("dep;1.jar", new byte[]{1}));
        assertThatThrownBy(() -> new PluginStager(config(Map.of()))
                .stage(new CountingSource(zip), "r1", List.of(new PluginSpec(U2, "bundle.zip"))))
                .isInstanceOf(ArtifactValidationException.class)
                .hasMessageContaining("must match");
    }

    @Test
    @DisplayName("crashed staging/eviction leftovers (*.tmpdir / *.evict.tmp) are garbage-collected by the sweep")
    void leftoverTmpDirsCollected() throws IOException {
        Path root = base.resolve("plugins");
        Files.createDirectories(root.resolve(U2 + ".tmpdir"));
        Files.write(root.resolve(U2 + ".tmpdir").resolve("partial.jar"), new byte[]{1});
        Files.createDirectories(root.resolve(U3 + ".evict.tmp"));
        Files.write(root.resolve(U3 + ".evict.tmp").resolve("old.jar"), new byte[]{2});

        CountingSource source = new CountingSource(new byte[]{7});
        new PluginStager(config(Map.of()))
                .stage(source, "r1", List.of(new PluginSpec(U1, "a.jar")));

        assertThat(root.resolve(U2 + ".tmpdir")).doesNotExist();
        assertThat(root.resolve(U3 + ".evict.tmp")).doesNotExist();
        assertThat(root.resolve(U1 + ".jar")).exists();
    }

    @Test
    @DisplayName("a cached jar is a cache hit — the source is never consulted")
    void jarCacheHitSkipsFetch() throws IOException {
        Path cached = base.resolve("plugins").resolve(U1 + ".jar");
        Files.createDirectories(cached.getParent());
        Files.write(cached, new byte[]{1, 2, 3});

        CountingSource source = new CountingSource(new byte[]{9});
        new PluginStager(config(Map.of()))
                .stage(source, "r1", List.of(new PluginSpec(U1, "a.jar")));
        assertThat(source.fetches.get()).isZero();
        assertThat(cached).hasBinaryContent(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("an uncached jar is downloaded once and staged atomically")
    void jarDownloaded() throws IOException {
        CountingSource source = new CountingSource(new byte[]{7, 7});
        PluginStager stager = new PluginStager(config(Map.of()));
        stager.stage(source, "r1", List.of(new PluginSpec(U1, "a.jar")));
        assertThat(source.fetches.get()).isEqualTo(1);
        assertThat(base.resolve("plugins").resolve(U1 + ".jar")).hasBinaryContent(new byte[]{7, 7});
        assertThat(stager.resolveJars(List.of(new PluginSpec(U1, "a.jar"))))
                .containsExactly(base.resolve("plugins").resolve(U1 + ".jar").toAbsolutePath().toString());
    }

    @Test
    @DisplayName("an empty-returning source (HTTP_UPLOAD) fails an uncached plugin with a clear IOException")
    void emptySourceFails() {
        CountingSource source = new CountingSource(null);
        assertThatThrownBy(() -> new PluginStager(config(Map.of()))
                .stage(source, "r1", List.of(new PluginSpec(U1, "a.jar"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ARTIFACT_SOURCE");
    }

    @Test
    @DisplayName("a bundle extracts flat jars, and resolveJars orders them by name")
    void bundleExtracts() throws IOException {
        byte[] zip = zipOf(Map.of("b.jar", new byte[]{2}, "a.jar", new byte[]{1}));
        PluginStager stager = new PluginStager(config(Map.of()));
        stager.stage(new CountingSource(zip), "r1", List.of(new PluginSpec(U2, "bundle.zip")));
        Path dir = base.resolve("plugins").resolve(U2);
        assertThat(dir).isDirectory();
        List<String> jars = stager.resolveJars(List.of(new PluginSpec(U2, "bundle.zip")));
        assertThat(jars).containsExactly(
                dir.resolve("a.jar").toAbsolutePath().toString(),
                dir.resolve("b.jar").toAbsolutePath().toString());
    }

    @Test
    @DisplayName("bundle entries with directories, non-jar extensions, or no jars at all are rejected")
    void bundleRejections() throws IOException {
        PluginStager stager = new PluginStager(config(Map.of()));

        byte[] nested = zipOf(Map.of("lib/x.jar", new byte[]{1}));
        assertThatThrownBy(() -> stager.stage(new CountingSource(nested), "r1",
                List.of(new PluginSpec(U2, "bundle.zip"))))
                .isInstanceOf(ArtifactValidationException.class).hasMessageContaining("flat");

        byte[] script = zipOf(Map.of("evil.sh", new byte[]{1}));
        assertThatThrownBy(() -> stager.stage(new CountingSource(script), "r1",
                List.of(new PluginSpec(U2, "bundle.zip"))))
                .isInstanceOf(ArtifactValidationException.class).hasMessageContaining(".jar");

        byte[] empty = zipOf(Map.of());
        assertThatThrownBy(() -> stager.stage(new CountingSource(empty), "r1",
                List.of(new PluginSpec(U2, "bundle.zip"))))
                .isInstanceOf(ArtifactValidationException.class).hasMessageContaining("no jars");

        assertThat(base.resolve("plugins").resolve(U2)).doesNotExist();
    }

    @Test
    @DisplayName("an oversize jar is rejected by the byte cap")
    void oversizeRejected() {
        byte[] big = new byte[2 * 1024 * 1024];
        assertThatThrownBy(() -> new PluginStager(config(Map.of("MAX_PLUGIN_SIZE_MB", "1")))
                .stage(new CountingSource(big), "r1", List.of(new PluginSpec(U1, "big.jar"))))
                .isInstanceOf(ArtifactValidationException.class)
                .hasMessageContaining("MB");
        assertThat(base.resolve("plugins").resolve(U1 + ".jar")).doesNotExist();
    }

    @Test
    @DisplayName("the sweep evicts oldest-mtime entries but never the current request's set")
    void sweepEvictsOldestSparesCurrent() throws IOException {
        Path root = base.resolve("plugins");
        Files.createDirectories(root);
        Path old = root.resolve(U2 + ".jar");
        Path older = root.resolve(U3 + ".jar");
        Files.write(old, new byte[]{1});
        Files.write(older, new byte[]{1});
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(old, FileTime.fromMillis(2_000));

        PluginStager stager = new PluginStager(config(Map.of("PLUGINS_CACHE_MAX_ENTRIES", "2")));
        stager.stage(new CountingSource(new byte[]{5}), "r1", List.of(new PluginSpec(U1, "a.jar")));

        assertThat(root.resolve(U1 + ".jar")).exists();     // current set is never evicted
        assertThat(older).doesNotExist();                    // oldest went first
        assertThat(old).exists();                            // bound (2) is respected
    }
}
