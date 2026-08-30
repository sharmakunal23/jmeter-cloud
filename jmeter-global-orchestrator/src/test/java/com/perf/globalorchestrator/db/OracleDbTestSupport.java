package com.perf.globalorchestrator.db;

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
 * stack: {@code oracle/initdb} creates the owner and the users on first boot,
 * and Flyway applies {@code oracle/migrations} (V1 metrics, V2 control plane,
 * the rendered group bundles) as the owner. Subclasses are {@code @Tag("db")}
 * and run only under {@code -PdbTests}.
 */
@Tag("db")
public abstract class OracleDbTestSupport {

    static final String PASSWORD = "localdev";
    /** The platform schema's owner — every table, unqualified names. */
    static final String OWNER = "CARDZATE_DB_GRAF";

    static final OracleContainer ORACLE = new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim"))
            .withPassword(PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(repoPath("oracle/initdb/01_createSchemasAndUsers.sql")),
                    "/container-entrypoint-initdb.d/01_createSchemasAndUsers.sql")
            .withStartupTimeout(Duration.ofMinutes(5));

    static {
        ORACLE.start();
        migrate(OWNER, "oracle/migrations");
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

    /** The owner — sees every table (ORCH_* and the group facts) for assertions. */
    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER, PASSWORD));
    }

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry) {
        // flyway-core is on the test classpath only for the static migrate()
        // above; Boot's auto-configuration would otherwise run Flyway again
        // as the application user, which holds no DDL privilege.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("ORACLE_METRICS_URL", OracleDbTestSupport::jdbcUrl);
        registry.add("ORACLE_METRICS_READER_USER", () -> "METRICS_READER");
        registry.add("ORACLE_METRICS_READER_PASSWORD", () -> PASSWORD);
        registry.add("ORACLE_METRICS_PURGER_USER", () -> "METRICS_PURGER");
        registry.add("ORACLE_METRICS_PURGER_PASSWORD", () -> PASSWORD);
        registry.add("ORACLE_GLOBALRUN_URL", OracleDbTestSupport::jdbcUrl);
        registry.add("ORACLE_GLOBALRUN_WRITER_USER", () -> "GLOBAL_ORCHESTRATOR_WRITER");
        registry.add("ORACLE_GLOBALRUN_WRITER_PASSWORD", () -> PASSWORD);
    }
}
