package com.perf.globalorchestrator.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A camelCase Oracle user must reach the driver quoted, exactly once. */
class DataSourceConfigTest {

    @Test
    void metrics_pools_switch_to_the_metrics_schema_and_reject_a_non_identifier() {
        assertEquals("ALTER SESSION SET CURRENT_SCHEMA = CARDZATE_DB_GRAF", DataSourceConfig.currentSchema("CARDZATE_DB_GRAF"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> DataSourceConfig.currentSchema("x; DROP TABLE RUN"));
    }

    @Test
    void usernames_are_quoted_exactly_once() {
        assertEquals("\"metricsReader\"", DataSourceConfig.quoted("metricsReader"));
        assertEquals("\"metricsReader\"", DataSourceConfig.quoted("\"metricsReader\""));
    }
}
