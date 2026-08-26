package com.perf.orchestrator.hygiene;

import com.perf.orchestrator.lifecycle.TestRunGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds a JMeter child that outlived its run and
 * gets rid of it.
 *
 * <h2>Why this matters now</h2>
 * With control-plane provisioning, a worker that ends up in a bad state is
 * recycled away — {@code PodRecycler} replaces the container and the
 * problem evaporates. An operator-declared worker is never recycled: it
 * runs for weeks. A stray JMeter holding a 2 GB heap would then quietly
 * degrade every subsequent run on that worker, and nothing in the platform
 * would notice, because the orchestrator's own view of state
 * ({@code recoverFromCrashIfNeeded}) only fixes what it believes — not what
 * the OS is actually running.
 *
 * <h2>Ownership — what counts as "ours"</h2>
 * A process is a candidate only when its command line names BOTH
 * {@code ApacheJMeter.jar} AND this orchestrator's {@code BASE_DIR}. The
 * second half is the load-bearing one: it is the marker that the process is
 * writing into <em>our</em> directories, so we cannot kill a co-tenant's
 * JMeter that happens to share the host. It also survives re-parenting —
 * scanning {@code ProcessHandle.current().descendants()} would miss an
 * orphan whose parent died and which init re-adopted, which is exactly the
 * case this class exists for.
 *
 * <h2>Only ever runs while idle</h2>
 * Callers gate on {@link TestRunGate#isRunning()}. During a run the live
 * child obviously matches the filter; killing it would be the bug, not the
 * fix.
 */
public final class OrphanJmeterReaper {

    private static final Logger LOG = LoggerFactory.getLogger(OrphanJmeterReaper.class);

    /** How the reaper responds to an orphan it finds. */
    public enum Policy {
        /** Terminate it (SIGTERM, then SIGKILL). The default. */
        KILL,
        /** Log it and surface it in readiness, but leave it running. */
        REPORT
    }

    private static final String JMETER_MARKER = "ApacheJMeter.jar";
    /** Grace between SIGTERM and SIGKILL. Short — the process is already unwanted. */
    private static final Duration TERM_GRACE = Duration.ofSeconds(5);

    private final TestRunGate gate;
    private final String baseDir;
    private final Policy policy;
    private final ProcessScanner scanner;

    /**
     * Volatile because the sweep runs on a scheduler thread while
     * {@code /api/v1/ready} reads it on a Tomcat thread. Null means clean.
     */
    private volatile String unresolvedOrphan;

    public OrphanJmeterReaper(TestRunGate gate, String baseDir, Policy policy) {
        this(gate, baseDir, policy, ProcessHandle::allProcesses);
    }

    /** Test seam — lets a unit test supply synthetic processes. */
    public OrphanJmeterReaper(TestRunGate gate, String baseDir, Policy policy, ProcessScanner scanner) {
        this.gate = gate;
        this.baseDir = baseDir;
        this.policy = policy;
        this.scanner = scanner;
    }

    /** Supplies the live process set. */
    @FunctionalInterface
    public interface ProcessScanner {
        Stream<ProcessHandle> allProcesses();
    }

    /**
     * One sweep. No-op while a test is in flight.
     *
     * @return what was found and what was done about it
     */
    public Scan sweep() {
        if (gate.isRunning()) {
            // A live run owns its child; nothing here is an orphan.
            return Scan.CLEAN;
        }
        List<ProcessHandle> orphans = findOrphans();
        if (orphans.isEmpty()) {
            unresolvedOrphan = null;
            return Scan.CLEAN;
        }

        List<Long> pids = orphans.stream().map(ProcessHandle::pid).toList();
        if (policy == Policy.REPORT) {
            LOG.warn("Found {} orphaned JMeter process(es) {} with no test in flight. "
                    + "ORPHAN_JMETER_POLICY=REPORT — leaving them running; this worker will "
                    + "keep degrading until they are cleared by hand.", pids.size(), pids);
            unresolvedOrphan = describe(pids, "not terminated (policy=REPORT)");
            return new Scan(pids.size(), 0, pids);
        }

        LOG.warn("Found {} orphaned JMeter process(es) {} with no test in flight — terminating. "
                + "A leftover child holds its full heap and would degrade every later run on "
                + "this worker.", pids.size(), pids);
        int killed = 0;
        List<Long> survivors = new ArrayList<>();
        for (ProcessHandle h : orphans) {
            if (terminate(h)) {
                killed++;
            } else {
                survivors.add(h.pid());
            }
        }
        if (survivors.isEmpty()) {
            unresolvedOrphan = null;
            LOG.info("Reaped {} orphaned JMeter process(es).", killed);
        } else {
            unresolvedOrphan = describe(survivors, "survived SIGTERM and SIGKILL");
            LOG.error("Could not terminate orphaned JMeter process(es) {} — this worker is "
                    + "poisoned and now reports NOT READY.", survivors);
        }
        return new Scan(pids.size(), killed, pids);
    }

    /**
     * Readiness signal: null when clean, otherwise an operator-readable
     * reason. Non-null means the worker must not be handed a new run —
     * either the kill failed or the policy is REPORT, and in both cases the
     * next run would contend with a full-heap leftover.
     */
    public String unresolvedOrphan() {
        return unresolvedOrphan;
    }

    private List<ProcessHandle> findOrphans() {
        try (Stream<ProcessHandle> all = scanner.allProcesses()) {
            return all.filter(this::isOurJmeter).toList();
        } catch (Exception e) {
            // Process enumeration can fail on a restricted platform. Not being
            // able to look is not evidence of an orphan; log once and move on.
            LOG.debug("Orphan scan could not enumerate processes: {}", e.toString());
            return List.of();
        }
    }

    private boolean isOurJmeter(ProcessHandle handle) {
        Optional<String> commandLine = handle.info().commandLine();
        if (commandLine.isEmpty()) {
            // Command line is unreadable for processes we don't own — which is
            // exactly the set we must not touch.
            return false;
        }
        String cl = commandLine.get();
        return cl.contains(JMETER_MARKER) && cl.contains(baseDir);
    }

    /** SIGTERM, brief grace, then SIGKILL. @return true when the process is gone */
    private boolean terminate(ProcessHandle handle) {
        try {
            handle.destroy();
            if (!handle.isAlive()) return true;
            handle.onExit().orTimeout(TERM_GRACE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .exceptionally(t -> null)
                    .join();
            if (!handle.isAlive()) return true;
            LOG.warn("Orphan JMeter pid {} ignored SIGTERM after {} — escalating to SIGKILL.",
                    handle.pid(), TERM_GRACE);
            handle.destroyForcibly();
            handle.onExit().orTimeout(TERM_GRACE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .exceptionally(t -> null)
                    .join();
            return !handle.isAlive();
        } catch (Exception e) {
            LOG.warn("Failed to terminate orphan JMeter pid {}: {}", handle.pid(), e.toString());
            return !handle.isAlive();
        }
    }

    private static String describe(List<Long> pids, String what) {
        return "orphanJmeterProcess: pid(s) " + pids + " " + what;
    }

    /**
     * @param found   orphaned JMeter processes seen this sweep
     * @param killed  how many were terminated
     * @param pids    the pids involved, for logging and assertions
     */
    public record Scan(int found, int killed, List<Long> pids) {
        static final Scan CLEAN = new Scan(0, 0, List.of());
    }
}
