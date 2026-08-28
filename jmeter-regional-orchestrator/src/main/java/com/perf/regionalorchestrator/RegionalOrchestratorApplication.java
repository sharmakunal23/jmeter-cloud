package com.perf.regionalorchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The in-cluster arm of the global orchestrator: creates and deletes worker
 * Pods through the cluster's own ServiceAccount and relays the global's calls
 * to workers whose DNS is cluster-private. Holds no state — every fact about a
 * worker lives in the global's registry or in the Pod itself.
 */
@SpringBootApplication
public class RegionalOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(RegionalOrchestratorApplication.class, args);
    }
}
