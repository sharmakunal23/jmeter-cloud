package com.perf.globalorchestrator.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A camelCase Oracle user must reach the driver quoted, exactly once. */
class DataSourceConfigTest {

    @Test
    void usernames_are_quoted_exactly_once() {
        assertEquals("\"metricsReader\"", DataSourceConfig.quoted("metricsReader"));
        assertEquals("\"metricsReader\"", DataSourceConfig.quoted("\"metricsReader\""));
    }
}
