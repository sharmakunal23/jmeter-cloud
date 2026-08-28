package com.perf.metricsconsumer.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * One Oracle Free container per test JVM, initialised exactly like the local
 * stack: {@code oracle/initdb} creates the owners and users on first boot, and
 * Flyway applies {@code oracle/migrations/metrics} as the {@code metrics} owner.
 * Subclasses are {@code @Tag("db")} and run only under {@code -PdbTests}.
 */
@Tag("db")
public abstract class OracleDbTestSupport {

    static final String PASSWORD = "localdev";

    static final OracleContainer ORACLE = new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim"))
            .withPassword(PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(repoPath("oracle/initdb/01_createSchemasAndUsers.sql")),
                    "/container-entrypoint-initdb.d/01_createSchemasAndUsers.sql")
            .withStartupTimeout(Duration.ofMinutes(5));

    static {
        ORACLE.start();
        migrate("metrics", "oracle/migrations/metrics");
    }

    static String jdbcUrl() {
        return "jdbc:oracle:thin:@//" + ORACLE.getHost() + ":" + ORACLE.getMappedPort(1521) + "/FREEPDB1";
    }

    static void migrate(String owner, String location) {
        Flyway.configure()
                .dataSource(jdbcUrl(), owner, PASSWORD)
                .locations("filesystem:" + repoPath(location))
                .load()
                .migrate();
    }

    static Path repoPath(String relative) {
        return Paths.get("..", relative).toAbsolutePath().normalize();
    }

    /** The schema owner — sees every table, can call the packages directly. */
    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), "metrics", PASSWORD));
    }

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry) {
        // flyway-core is on the test classpath only for the static migrate()
        // above; Boot's auto-configuration would otherwise run Flyway again
        // as the application user, which holds no DDL privilege.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("DB_METRICS_URL", OracleDbTestSupport::jdbcUrl);
        registry.add("DB_METRICS_USER", () -> "metricsWriter");
        registry.add("DB_METRICS_PASSWORD", () -> PASSWORD);
    }
}
