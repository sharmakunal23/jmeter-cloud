package com.perf.orchestrator.lifecycle;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Launches a JMeter child process for one test run.
 *
 * <p>Single-method seam so {@link TestRunManager} can be exercised against
 * a fake launcher in unit tests, while the production
 * {@link JmeterProcessManager} spawns the real binary.
 */
@FunctionalInterface
public interface JmeterLauncher {

    JmeterProcess launch(LaunchSpec spec) throws IOException;

    /**
     * Everything the launcher needs to spawn JMeter for one run. Immutable;
     * built once per run by {@link TestRunManager} from the per-run
     * {@code OrchestratorConfig}.
     */
    record LaunchSpec(
            List<String> command,
            Map<String, String> env,
            Path workingDir,
            Path stdoutLog) {

        public LaunchSpec {
            command = List.copyOf(command);
            env     = Map.copyOf(env);
        }
    }
}
