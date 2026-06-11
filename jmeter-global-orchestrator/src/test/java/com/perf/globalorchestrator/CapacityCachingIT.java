package com.perf.globalorchestrator;

import com.perf.globalorchestrator.config.CacheConfig;
import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.ApplicationCapacity;
import com.perf.globalorchestrator.domain.RecyclePolicy;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.repo.ApplicationCapacityRepository;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CACHE C-EVICT — write-through invalidation IT for the per-(app, region)
 * capacity grid. Asserts the contract directly via the {@link CacheManager}
 * (the most faithful proxy for "is the entry stale?"): a read populates the
 * cache, and every mutating path clears it so the next read reflects the write
 * with no TTL wait.
 *
 * <p>Runs against Testcontainers Postgres with the {@code simple} cache
 * provider (from the test {@code application-local.yml}). The capacity-grid
 * write paths covered:
 * <ul>
 *   <li>{@code upsert} — the {@code PUT /capacity/{region}} path.</li>
 *   <li>{@code replaceAll} — the app-create seed path.</li>
 *   <li>app delete — the {@code ON DELETE CASCADE} that bypasses the
 *       repository (evicted by {@code ApplicationController.delete}).</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator capacity grid — write-through eviction IT (CACHE C-EVICT)")
class CapacityCachingIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jmetercloud_globalrun")
            .withUsername("postgres")
            .withPassword("test")
            .withInitScript("createTestUsers.sql");

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry) {
        registry.add("POSTGRES_METRICS_URL",   POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_USER",          () -> "postgres");
        registry.add("POSTGRES_PASSWORD",      () -> "test");
        registry.add("POSTGRES_GLOBALRUN_URL", POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_GLOBALRUN_WRITER_USER",     () -> "globalOrchestratorWriter");
        registry.add("POSTGRES_GLOBALRUN_WRITER_PASSWORD", () -> "test");
        registry.add("GLOBAL_ORCHESTRATOR_REGION", () -> "us-east-1");
        registry.add("globalOrchestrator.pod.sweepInitialDelayMs", () -> "3600000");
        registry.add("globalOrchestrator.pod.lostAfterMs",         () -> "3600000");
        // Keep the health poller from racing the test by writing app rows.
        registry.add("globalOrchestrator.application.healthPoll.initialDelayMs", () -> "3600000");
    }

    // The app-delete path proxies a Kafka topic delete; mock it so the IT
    // needs no broker.
    @MockBean
    com.perf.globalorchestrator.kafka.KafkaTopicProvisioner topicProvisioner;

    @Autowired MockMvc mvc;
    @Autowired ApplicationRepository appRepo;
    @Autowired ApplicationCapacityRepository capacityRepo;
    @Autowired CacheManager cacheManager;

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Path globalRun = Paths.get("..", "postgres", "migrations", "globalrun").toAbsolutePath().normalize();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + globalRun)
                .table("flyway_schema_history_globalrun")
                .baselineOnMigrate(true).baselineVersion("0")
                .load().migrate();
        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
    }

    @AfterEach
    void cleanFixtures() {
        // applicationCapacity FK → application ON DELETE CASCADE handles the children.
        jdbc.update("DELETE FROM \"globalOrchestrator\".\"application\"");
    }

    private Cache capacityCache() {
        return cacheManager.getCache(CacheConfig.CACHE_APPLICATION_CAPACITY);
    }

    private String seedApp(String name) {
        Application app = new Application(
                Ulid.generate(), name, null, null, List.of(), null, Instant.now(),
                null, null, null, RecyclePolicy.REUSE, null, null, false);
        return appRepo.insert(app).applicationId();
    }

    @Test
    @DisplayName("findByApplicationId caches; upsert evicts; re-read reflects the new value")
    void upsertEvictsPerAppEntry() {
        String appId = seedApp("capcache-upsert");

        capacityRepo.findByApplicationId(appId);
        assertThat(capacityCache().get(appId)).as("read should populate the per-app entry").isNotNull();

        capacityRepo.upsert(appId, "us-east", 5);
        assertThat(capacityCache().get(appId)).as("upsert must evict the cached entry").isNull();

        List<ApplicationCapacity> after = capacityRepo.findByApplicationId(appId);
        assertThat(after).singleElement()
                .satisfies(c -> {
                    assertThat(c.region()).isEqualTo("us-east");
                    assertThat(c.maxAvailable()).isEqualTo(5);
                });
        assertThat(capacityCache().get(appId)).as("re-read should re-populate the cache").isNotNull();
    }

    @Test
    @DisplayName("findAllGroupedByApp caches under 'all'; replaceAll evicts it")
    void replaceAllEvictsGroupedEntry() {
        String appId = seedApp("capcache-replaceall");

        capacityRepo.findAllGroupedByApp();
        assertThat(capacityCache().get("all")).as("grouped read should populate the 'all' entry").isNotNull();

        capacityRepo.replaceAll(appId, List.of(new ApplicationCapacity(appId, "us-east", 3, null, null)));
        assertThat(capacityCache().get("all")).as("replaceAll must evict the grouped entry").isNull();

        assertThat(capacityRepo.findAllGroupedByApp().get(appId))
                .singleElement()
                .satisfies(c -> assertThat(c.maxAvailable()).isEqualTo(3));
    }

    @Test
    @DisplayName("DELETE /applications/{id}: ON DELETE CASCADE bypasses the repo; controller evicts the cache")
    void appDeleteCascadeEvicts() throws Exception {
        String appId = seedApp("capcache-delete");
        capacityRepo.upsert(appId, "us-east", 2);          // capacity row to be cascaded (also evicts)
        capacityRepo.findByApplicationId(appId);            // miss → reads + re-populates the cache
        assertThat(capacityCache().get(appId)).isNotNull();

        mvc.perform(delete("/api/v1/applications/{id}", appId))
                .andExpect(status().isNoContent());

        assertThat(capacityCache().get(appId))
                .as("app delete (cascade) must evict the cached capacity entry")
                .isNull();
    }
}
