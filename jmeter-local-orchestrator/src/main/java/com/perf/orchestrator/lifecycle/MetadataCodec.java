package com.perf.orchestrator.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the JSON companion files that {@link ArtifactStager} keeps
 * next to the uploaded artifacts.
 *
 * <p>Hand-rolled with Jackson's {@link JsonNode} API rather than POJO
 * binding so the codec has zero coupling to controller-layer types and the
 * on-disk shape stays explicit. Writes go through a tmp + atomic rename so
 * a crash mid-write can never produce a half-formed manifest.
 */
class MetadataCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    void writePlanMetadata(Path target, PlanMetadata meta) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("filename",   meta.filename());
        node.put("sizeBytes",  meta.sizeBytes());
        node.put("sha256",     meta.sha256());
        node.put("uploadedAt", meta.uploadedAt().toString());
        node.put("compressed", meta.compressed());
        writeAtomically(target, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
    }

    PlanMetadata readPlanMetadata(Path source) throws IOException {
        JsonNode n = MAPPER.readTree(source.toFile());
        return new PlanMetadata(
                n.path("filename").asText(""),
                n.path("sizeBytes").asLong(0L),
                n.path("sha256").asText(""),
                Instant.parse(n.path("uploadedAt").asText()),
                n.path("compressed").asBoolean(false));
    }

    void writeDataFilesManifest(Path target, DataFilesManifest manifest) throws IOException {
        writeAtomically(target, encodeDataFilesManifest(manifest));
    }

    /**
     * Writes the manifest bytes directly to {@code path} with no tmp+rename
     * dance — used by {@link ArtifactStager} when it owns the rename as part
     * of a larger reversible swap. Failures here surface before any other
     * swap commits, so the caller can abort with previous state intact.
     */
    void writeDataFilesManifestRaw(Path path, DataFilesManifest manifest) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(path, encodeDataFilesManifest(manifest),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static byte[] encodeDataFilesManifest(DataFilesManifest manifest) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("zipSizeBytes",   manifest.zipSizeBytes());
        node.put("extractedBytes", manifest.extractedBytes());
        node.put("fileCount",      manifest.fileCount());
        ArrayNode files = node.putArray("files");
        manifest.files().forEach(files::add);
        node.put("sha256",     manifest.sha256());
        node.put("uploadedAt", manifest.uploadedAt().toString());
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
    }

    DataFilesManifest readDataFilesManifest(Path source) throws IOException {
        JsonNode n = MAPPER.readTree(source.toFile());
        List<String> files = new ArrayList<>();
        n.path("files").forEach(f -> files.add(f.asText()));
        return new DataFilesManifest(
                n.path("zipSizeBytes").asLong(0L),
                n.path("extractedBytes").asLong(0L),
                n.path("fileCount").asInt(0),
                files,
                n.path("sha256").asText(""),
                Instant.parse(n.path("uploadedAt").asText()));
    }

    private static void writeAtomically(Path target, byte[] payload) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, payload,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
