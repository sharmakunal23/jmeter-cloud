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
 * Two datasources, two JdbcTemplates. The global-orchestrator straddles
 * two databases:
 *
 * <ul>
 *   <li><strong>Run-state</strong> ({@code jmetercloud_globalrun}) —
 *       READ/WRITE, owns {@code globalOrchestrator.run} and
 *       {@code globalOrchestrator.runFleetMember}. Connected as the
 *       per-app {@code globalOrchestratorWriter} role. <strong>Primary</strong>
 *       so Spring Boot's auto-configured {@code DataSourceHealthIndicator}
 *       picks it up.</li>
 *   <li><strong>Metrics</strong> ({@code jmetercloud_metrics}) — READ-ONLY,
 *       reads from {@code metrics."workerMetric"} for the rollup endpoint.
 *       Connected as the per-app {@code metricsReader} role.</li>
 *   <li><strong>Metrics-purge</strong> ({@code jmetercloud_metrics}) —
 *       READ-WRITE but used by ONE path only: the HARD-DELETE / purge of a
 *       hidden run's per-second rows. Connected as the dedicated
 *       {@code metricsPurger} role (SELECT + DELETE). Kept separate from the
 *       read pool so the hot read path stays {@code setReadOnly(true)} and the
 *       DELETE privilege has a single, purpose-built blast radius. Pool is
 *       small + lazily used — purge is an infrequent operator action.</li>
 * </ul>
 *
 * <p>Hikari pool sizes follow the Step 11 / Phase 2 perf doc — bump for
 * production fleet load.
 */
@Configuration
public class DataSourceConfig {

    // ── Run-state DS (writer) — primary ─────────────────────────────────

    @Bean
    @Primary
    @Qualifier("runStateDataSource")
    public DataSource runStateDataSource(
            @Value("${globalOrchestrator.runStateUrl:jdbc:postgresql://postgres:5432/jmetercloud_globalrun}")
            String url,
            @Value("${globalOrchestrator.runStateUser:globalOrchestratorWriter}") String user,
            @Value("${globalOrchestrator.runStatePassword:localdev}") String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setPoolName("globalOrchestratorRunStatePool");
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(2);
        cfg.setReadOnly(false);
        return new HikariDataSource(cfg);
    }

    @Bean
    @Primary
    @Qualifier("runStateJdbcTemplate")
    public JdbcTemplate runStateJdbcTemplate(@Qualifier("runStateDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    // ── Metrics DS (reader) — secondary ─────────────────────────────────

    @Bean
    @Qualifier("metricsDataSource")
    public DataSource metricsDataSource(
            @Value("${spring.datasource.url:jdbc:postgresql://postgres:5432/jmetercloud_metrics}")
            String url,
            @Value("${spring.datasource.username:metricsReader}") String user,
            @Value("${spring.datasource.password:localdev}") String password,
            @Value("${globalOrchestrator.metricsStatementTimeoutMs:30000}") int statementTimeoutMs) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setPoolName("globalOrchestratorMetricsPool");
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(2);
        cfg.setReadOnly(true);
        // SECURITY S-3 — bound runaway read queries (e.g. a /timeseries scan
        // over a huge run) so one bad request can't tie up a pooled connection
        // indefinitely. Set as a session default on every physical connection
        // when it's created; <=0 disables it. Postgres takes the value in ms.
        // App-side backstop — the strongest enforcement is role-level
        // (ALTER ROLE metricsReader SET statement_timeout), enforced server-side
        // regardless of client; this guarantees it even if the role grant drifts.
        if (statementTimeoutMs > 0) {
            cfg.setConnectionInitSql("SET statement_timeout TO " + statementTimeoutMs);
        }
        return new HikariDataSource(cfg);
    }

    @Bean
    @Qualifier("metricsJdbcTemplate")
    public JdbcTemplate metricsJdbcTemplate(@Qualifier("metricsDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    // ── Metrics-purge DS (purge-only writer) ────────────────────────────

    @Bean
    @Qualifier("metricsPurgeDataSource")
    public DataSource metricsPurgeDataSource(
            @Value("${globalOrchestrator.metricsPurgeUrl:jdbc:postgresql://postgres:5432/jmetercloud_metrics}")
            String url,
            @Value("${globalOrchestrator.metricsPurgeUser:metricsPurger}") String user,
            @Value("${globalOrchestrator.metricsPurgePassword:localdev}") String password,
            @Value("${globalOrchestrator.metricsPurgeStatementTimeoutMs:120000}") int statementTimeoutMs) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setPoolName("globalOrchestratorMetricsPurgePool");
        // Purge is infrequent + operator-driven; a tiny pool is plenty and keeps
        // the DELETE-capable connection count minimal.
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(0);
        cfg.setReadOnly(false);
        // Lazy init — do NOT probe a connection at startup. Purge is infrequent
        // and operator-driven, so booting must not depend on the metricsPurger
        // credential being reachable; the first actual purge surfaces any
        // connectivity problem. This also keeps every non-purge path (and the
        // test suite, which never purges) decoupled from this pool.
        cfg.setInitializationFailTimeout(-1);
        // A run-scoped DELETE scans every weekly partition (runId is not the
        // partition key), so it can run longer than a read query — a more
        // generous default timeout than the read pool's 30 s, but still bounded
        // so a pathological purge can't pin the connection forever.
        if (statementTimeoutMs > 0) {
            cfg.setConnectionInitSql("SET statement_timeout TO " + statementTimeoutMs);
        }
        return new HikariDataSource(cfg);
    }

    @Bean
    @Qualifier("metricsPurgeJdbcTemplate")
    public JdbcTemplate metricsPurgeJdbcTemplate(@Qualifier("metricsPurgeDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
