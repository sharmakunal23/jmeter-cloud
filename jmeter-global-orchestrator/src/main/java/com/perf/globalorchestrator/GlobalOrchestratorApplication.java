package com.perf.globalorchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Fan-out layer that drives many {@code jmeter-local-orchestrator}
 * instances across regions / clusters.
 *
 * <p>{@code @EnableScheduling} is on for Step 15's
 * {@link com.perf.globalorchestrator.sweep.PodSweeper} — the only
 * scheduled bean today; flips pods to LOST when their heartbeat
 * goes stale.
 */
@SpringBootApplication
@EnableScheduling
public class GlobalOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(GlobalOrchestratorApplication.class, args);
    }
}
