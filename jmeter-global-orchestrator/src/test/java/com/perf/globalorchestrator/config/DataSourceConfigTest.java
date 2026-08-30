package com.perf.globalorchestrator.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Every pool switches to the platform schema; the name is validated as an UPPER_SNAKE identifier first. */
class DataSourceConfigTest {

    @Test
    void pools_switch_to_the_platform_schema_and_reject_a_non_identifier() {
        assertEquals("ALTER SESSION SET CURRENT_SCHEMA = CARDZATE_DB_GRAF", DataSourceConfig.currentSchema("CARDZATE_DB_GRAF"));
        assertThrows(IllegalArgumentException.class, () -> DataSourceConfig.currentSchema("x; DROP TABLE RUN"));
        assertThrows(IllegalArgumentException.class, () -> DataSourceConfig.currentSchema("cardzate_db_graf"));   // the name IS upper
    }
}
