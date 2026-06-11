package com.perf.documentservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavior IT for the {@code S3BlobStore} backend, running against a
 * LocalStack-emulated S3.
 *
 * <p>Drives the same HTTP contract as {@code BlobLifecycleIT} but routes
 * through the cloud-profile {@code S3BlobStore} bean. Two scenarios:
 * <ol>
 *   <li>POST → 201; GET metadata + bytes round-trip; DELETE → 204; subsequent GET → 404.</li>
 *   <li>Malformed blobId is rejected as 404 without an S3 round-trip.</li>
 * </ol>
 *
 * <p>Compiled only when the {@code -Pcloud} Maven profile is active (the
 * source root is added by {@code build-helper-maven-plugin}). Skipped on
 * default builds because the AWS SDK + LocalStack image aren't available.
 */
@SpringBootTest(properties = {
        "documentService.backend=s3",
        "documentService.s3.bucket=docsvc-it",
        "documentService.s3.pathStyle=true"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("S3BlobStore — behavior IT against LocalStack")
class S3BlobStoreIT {

    @Container
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.5"))
                    .withServices(Service.S3);

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry) {
        registry.add("documentService.s3.region",   () -> LOCALSTACK.getRegion());
        registry.add("documentService.s3.endpoint", () -> LOCALSTACK.getEndpointOverride(Service.S3).toString());
        // The AWS SDK's default credentials provider chain reads these
        // system properties (via SystemPropertyCredentialsProvider).
        registry.add("aws.accessKeyId",     () -> LOCALSTACK.getAccessKey());
        registry.add("aws.secretAccessKey", () -> LOCALSTACK.getSecretKey());
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client admin = S3Client.builder()
                .region(Region.of(LOCALSTACK.getRegion()))
                .endpointOverride(LOCALSTACK.getEndpointOverride(Service.S3))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .forcePathStyle(true)
                .build()) {
            admin.createBucket(b -> b.bucket("docsvc-it"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    @DisplayName("POST → S3; GET metadata + bytes round-trip; DELETE → 404 thereafter")
    void roundTripAndDelete() throws Exception {
        byte[] payload = "Hello from LocalStack — Step 13 round-trip".getBytes();
        String expectedSha = sha256Hex(payload);

        MvcResult uploadResult = mvc.perform(post("/api/v1/blob")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blobId").exists())
                .andExpect(jsonPath("$.sizeBytes").value(payload.length))
                .andExpect(jsonPath("$.sha256").value(expectedSha))
                .andReturn();

        JsonNode body = mapper.readTree(uploadResult.getResponse().getContentAsString());
        String blobId = body.get("blobId").asText();
        assertThat(blobId).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]+");

        mvc.perform(get("/api/v1/blob/{id}/metadata", blobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sha256").value(expectedSha))
                .andExpect(jsonPath("$.sizeBytes").value(payload.length));

        mvc.perform(get("/api/v1/blob/{id}", blobId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-sha256", expectedSha))
                .andExpect(content().bytes(payload));

        mvc.perform(delete("/api/v1/blob/{id}", blobId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/blob/{id}", blobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BLOB_NOT_FOUND"));
    }

    @Test
    @DisplayName("Malformed blobId short-circuits to 404 (no S3 round-trip)")
    void malformedBlobIdIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/blob/{id}", "../etc/passwd"))
                .andExpect(status().isNotFound());
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
