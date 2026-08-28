package com.perf.regionalorchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** The context boots with only REGION set — no cluster, no hub. */
@SpringBootTest(properties = "regionalOrchestrator.region=na-east")
class RegionalOrchestratorApplicationTest {

    @Test
    void contextLoads() {
    }
}
