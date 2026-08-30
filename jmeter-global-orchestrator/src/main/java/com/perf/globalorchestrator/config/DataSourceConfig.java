package com.perf.globalorchestrator.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Three Hikari pools against the one platform schema ({@code CARDZATE_DB_GRAF}),
 * one per role: the run-state writer ({@code GLOBAL_ORCHESTRATOR_WRITER},
 * primary — Boot's {@code DataSourceHealthIndicator} picks it up), the metrics
 * reader ({@code METRICS_READER}, SELECT only) and the metrics purger
 * ({@code METRICS_PURGER}, the one DELETE-capable path, lazily initialised).
 *
 * <p>Every pool sets {@code CURRENT_SCHEMA} to the platform schema, so each
 * statement names its table bare ({@code ORCH_RUN}, {@code CPS_METRICS}) exactly
 * as the hosted consumer does. Query timeouts are set on the
 * {@link JdbcTemplate}s — Oracle has no session statement timeout to set as
 * init SQL.
 */
@Configuration
public class DataSourceConfig {

    // ── Run-state DS (writer) — primary ─────────────────────────────────

    @Bean
    @Primary
    @Qualifier("runStateDataSource")
    public DataSource runStateDataSource(
            @Value("${globalOrchestrator.runStateUrl:jdbc:oracle:thin:@//oracle:1521/FREEPDB1}")
            String url,
            @Value("${globalOrchestrator.runStateUser:GLOBAL_ORCHESTRATOR_WRITER}") String user,
            @Value("${globalOrchestrator.runStatePassword:localdev}") String password,
            @Value("${globalOrchestrator.schema:CARDZATE_DB_GRAF}") String schema) {
        HikariConfig cfg = pool(url, user, password, "globalOrchestratorRunStatePool");
        cfg.setConnectionInitSql(currentSchema(schema));
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(2);
        return new HikariDataSource(cfg);
    }

    @Bean
    @Primary
    @Qualifier("runStateJdbcTemplate")
    public JdbcTemplate runStateJdbcTemplate(
            @Qualifier("runStateDataSource") DataSource ds,
            @Value("${globalOrchestrator.runStateStatementTimeoutMs:30000}") int statementTimeoutMs) {
        return template(ds, statementTimeoutMs);
    }

    // ── Metrics DS (reader) — secondary ─────────────────────────────────

    @Bean
    @Qualifier("metricsDataSource")
    public DataSource metricsDataSource(
            @Value("${spring.datasource.url:jdbc:oracle:thin:@//oracle:1521/FREEPDB1}")
            String url,
            @Value("${spring.datasource.username:METRICS_READER}") String user,
            @Value("${spring.datasource.password:localdev}") String password,
            @Value("${globalOrchestrator.schema:CARDZATE_DB_GRAF}") String schema) {
        HikariConfig cfg = pool(url, user, password, "globalOrchestratorMetricsPool");
        cfg.setConnectionInitSql(currentSchema(schema));
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(2);
        // The role's grants (SELECT only) are the enforcement; this flag is a
        // hint to the driver, not a guarantee.
        cfg.setReadOnly(true);
        return new HikariDataSource(cfg);
    }

    @Bean
    @Qualifier("metricsJdbcTemplate")
    public JdbcTemplate metricsJdbcTemplate(
            @Qualifier("metricsDataSource") DataSource ds,
            // SECURITY S-3 — bound runaway read queries (e.g. a /timeseries scan
            // over a huge run) so one bad request can't tie up a pooled
            // connection indefinitely. <= 0 disables it.
            @Value("${globalOrchestrator.metricsStatementTimeoutMs:30000}") int statementTimeoutMs) {
        return template(ds, statementTimeoutMs);
    }

    // ── Metrics-purge DS (purge-only writer) ────────────────────────────

    @Bean
    @Qualifier("metricsPurgeDataSource")
    public DataSource metricsPurgeDataSource(
            @Value("${globalOrchestrator.metricsPurgeUrl:jdbc:oracle:thin:@//oracle:1521/FREEPDB1}")
            String url,
            @Value("${globalOrchestrator.metricsPurgeUser:METRICS_PURGER}") String user,
            @Value("${globalOrchestrator.metricsPurgePassword:localdev}") String password,
            @Value("${globalOrchestrator.schema:CARDZATE_DB_GRAF}") String schema) {
        HikariConfig cfg = pool(url, user, password, "globalOrchestratorMetricsPurgePool");
        cfg.setConnectionInitSql(currentSchema(schema));
        // Purge is infrequent + operator-driven; a tiny pool is plenty and keeps
        // the DELETE-capable connection count minimal.
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(0);
        // Lazy init — do NOT probe a connection at startup. Booting must not
        // depend on the METRICS_PURGER credential being reachable; the first
        // actual purge surfaces any connectivity problem, and the test suite
        // (which never purges) stays decoupled from this pool.
        cfg.setInitializationFailTimeout(-1);
        return new HikariDataSource(cfg);
    }

    @Bean
    @Qualifier("metricsPurgeJdbcTemplate")
    public JdbcTemplate metricsPurgeJdbcTemplate(
            @Qualifier("metricsPurgeDataSource") DataSource ds,
            // A run-scoped DELETE removes every row a whole test produced, so it
            // gets a more generous bound than the read pool's 30 s — still
            // bounded so a pathological purge can't pin the connection forever.
            @Value("${globalOrchestrator.metricsPurgeStatementTimeoutMs:120000}") int statementTimeoutMs) {
        return template(ds, statementTimeoutMs);
    }

    // ── Shared shape ────────────────────────────────────────────────────

    private static HikariConfig pool(String url, String user, String password, String name) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setPoolName(name);
        // The repositories reuse a fixed set of statements; the implicit
        // cache skips re-parsing them on each pooled connection.
        cfg.addDataSourceProperty("oracle.jdbc.implicitStatementCacheSize", "32");
        // ojdbc fetches 10 rows per round trip by default; a timeseries poll
        // reads ~1,800 rollup rows, so that would be 180 round trips.
        cfg.addDataSourceProperty("oracle.jdbc.defaultRowPrefetch", String.valueOf(ROW_PREFETCH));
        return cfg;
    }

    /** Rows per fetch round trip — covers a whole 30-minute live poll in one or two fetches. */
    static final int ROW_PREFETCH = 1000;

    private static JdbcTemplate template(DataSource ds, int statementTimeoutMs) {
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.setFetchSize(ROW_PREFETCH);
        if (statementTimeoutMs > 0) {
            jdbc.setQueryTimeout(Math.max(1, statementTimeoutMs / 1000));
        }
        return jdbc;
    }

    /**
     * Every pool resolves unqualified names in the platform schema
     * ({@code CARDZATE_DB_GRAF}), so the hub's SQL is the hosted consumer's SQL.
     * The name is an identifier, validated before it is spliced.
     */
    static String currentSchema(String schema) {
        if (!schema.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("schema is not an identifier: " + schema);
        }
        return "ALTER SESSION SET CURRENT_SCHEMA = " + schema;
    }
}
