package com.perf.orchestrator.hygiene;

import com.perf.orchestrator.lifecycle.TestRunGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OrphanJmeterReaper — a JMeter child that outlived its run")
class OrphanJmeterReaperTest {

    private static final String BASE_DIR = "/var/lib/jmeter-orchestrator";

    /** A JMeter launched by THIS orchestrator: names both the jar and our BASE_DIR. */
    private static ProcessHandle ourJmeter(long pid, boolean diesOnDestroy) {
        return handle(pid,
                "/opt/java/bin/java -Xmx2g -jar /opt/jmeter/bin/ApacheJMeter.jar -n "
                + "-t " + BASE_DIR + "/testPlans/plan.jmx -l " + BASE_DIR + "/results/r1/results.jtl",
                diesOnDestroy);
    }

    private static ProcessHandle handle(long pid, String commandLine, boolean diesOnDestroy) {
        ProcessHandle h = mock(ProcessHandle.class, RETURNS_DEEP_STUBS);
        when(h.pid()).thenReturn(pid);
        when(h.info().commandLine()).thenReturn(Optional.of(commandLine));
        when(h.onExit()).thenReturn(CompletableFuture.completedFuture(h));
        // isAlive flips to false once destroy() has been called, unless this
        // process is modelled as unkillable.
        when(h.isAlive()).thenReturn(true);
        if (diesOnDestroy) {
            when(h.destroy()).thenAnswer(inv -> {
                when(h.isAlive()).thenReturn(false);
                return true;
            });
        } else {
            when(h.destroy()).thenReturn(true);
            when(h.destroyForcibly()).thenReturn(true);
        }
        return h;
    }

    private static OrphanJmeterReaper reaper(TestRunGate gate,
                                             OrphanJmeterReaper.Policy policy,
                                             ProcessHandle... processes) {
        return new OrphanJmeterReaper(gate, BASE_DIR, policy, () -> Stream.of(processes));
    }

    @Test
    @DisplayName("kills a leftover child and clears the readiness signal")
    void killsOrphan() {
        ProcessHandle orphan = ourJmeter(4242, true);
        OrphanJmeterReaper r = reaper(() -> false, OrphanJmeterReaper.Policy.KILL, orphan);

        OrphanJmeterReaper.Scan scan = r.sweep();

        assertThat(scan.found()).isEqualTo(1);
        assertThat(scan.killed()).isEqualTo(1);
        assertThat(scan.pids()).containsExactly(4242L);
        assertThat(r.unresolvedOrphan()).isNull();
        verify(orphan).destroy();
    }

    @Test
    @DisplayName("NEVER touches a process while a test is in flight — during a run the live child "
            + "matches the same filter, and killing it would be the bug, not the fix")
    void doesNothingWhileARunIsActive() {
        ProcessHandle liveChild = ourJmeter(4242, true);
        OrphanJmeterReaper r = reaper(() -> true, OrphanJmeterReaper.Policy.KILL, liveChild);

        OrphanJmeterReaper.Scan scan = r.sweep();

        assertThat(scan.found()).isZero();
        verify(liveChild, never()).destroy();
        verify(liveChild, never()).destroyForcibly();
    }

    @Test
    @DisplayName("ignores a co-tenant's JMeter — ownership requires OUR baseDir on the command "
            + "line, so a JMeter sharing the host is never a candidate")
    void ignoresForeignJmeter() {
        ProcessHandle foreign = handle(777,
                "/usr/bin/java -jar /opt/jmeter/bin/ApacheJMeter.jar -n -t /other/tenant/plan.jmx",
                true);
        OrphanJmeterReaper r = reaper(() -> false, OrphanJmeterReaper.Policy.KILL, foreign);

        assertThat(r.sweep().found()).isZero();
        verify(foreign, never()).destroy();
    }

    @Test
    @DisplayName("ignores unrelated processes in our own tree")
    void ignoresNonJmeterProcesses() {
        ProcessHandle other = handle(99, "/bin/sh -c tail -f " + BASE_DIR + "/logs/x.log", true);
        OrphanJmeterReaper r = reaper(() -> false, OrphanJmeterReaper.Policy.KILL, other);

        assertThat(r.sweep().found()).isZero();
        verify(other, never()).destroy();
    }

    @Test
    @DisplayName("a process whose command line is unreadable is left alone — that is exactly the "
            + "set we do not own")
    void ignoresProcessesWithNoReadableCommandLine() {
        ProcessHandle opaque = mock(ProcessHandle.class, RETURNS_DEEP_STUBS);
        when(opaque.info().commandLine()).thenReturn(Optional.empty());
        OrphanJmeterReaper r = reaper(() -> false, OrphanJmeterReaper.Policy.KILL, opaque);

        assertThat(r.sweep().found()).isZero();
        verify(opaque, never()).destroy();
    }

    @Test
    @DisplayName("escalates to SIGKILL when SIGTERM is ignored")
    void escalatesToSigkill() {
        ProcessHandle stubborn = ourJmeter(4242, false);
        OrphanJmeterReaper r = reaper(() -> false, OrphanJmeterReaper.Policy.KILL, stubborn);

        r.sweep();

        verify(stubborn).destroy();
        verify(stubborn).destroyForcibly();
    }

    @Test
    @DisplayName("an unkillable orphan flags the worker NOT READY — this pod is genuinely "
            + "poisoned and must stop being handed runs")
    void unkillableOrphanFlagsNotReady() {
        OrphanJmeterReaper r = reaper(() -> false, OrphanJmeterReaper.Policy.KILL,
                ourJmeter(4242, false));

        OrphanJmeterReaper.Scan scan = r.sweep();

        assertThat(scan.killed()).isZero();
        assertThat(r.unresolvedOrphan())
                .isNotNull()
                .contains("4242")
                .contains("SIGKILL");
    }

    @Test
    @DisplayName("REPORT policy leaves the process running but still flags NOT READY")
    void reportPolicyDoesNotKill() {
        ProcessHandle orphan = ourJmeter(4242, true);
        OrphanJmeterReaper r = reaper(() -> false, OrphanJmeterReaper.Policy.REPORT, orphan);

        OrphanJmeterReaper.Scan scan = r.sweep();

        assertThat(scan.found()).isEqualTo(1);
        assertThat(scan.killed()).isZero();
        verify(orphan, never()).destroy();
        assertThat(r.unresolvedOrphan()).isNotNull().contains("REPORT");
    }

    @Test
    @DisplayName("a clean sweep after a dirty one clears the readiness signal")
    void signalClearsOnceResolved() {
        OrphanJmeterReaper dirty = reaper(() -> false, OrphanJmeterReaper.Policy.REPORT,
                ourJmeter(4242, true));
        dirty.sweep();
        assertThat(dirty.unresolvedOrphan()).isNotNull();

        OrphanJmeterReaper clean = new OrphanJmeterReaper(
                () -> false, BASE_DIR, OrphanJmeterReaper.Policy.REPORT, Stream::empty);
        assertThat(clean.sweep().found()).isZero();
        assertThat(clean.unresolvedOrphan()).isNull();
    }

    @Test
    @DisplayName("a scanner that throws is not evidence of an orphan — being unable to look must "
            + "not flip a healthy worker to NOT READY")
    void scannerFailureIsNotAnOrphan() {
        OrphanJmeterReaper r = new OrphanJmeterReaper(() -> false, BASE_DIR,
                OrphanJmeterReaper.Policy.KILL,
                () -> { throw new UnsupportedOperationException("no /proc"); });

        assertThat(r.sweep().found()).isZero();
        assertThat(r.unresolvedOrphan()).isNull();
    }

    @Test
    @DisplayName("kills every orphan it finds, not just the first")
    void killsAllOrphans() {
        List<ProcessHandle> orphans = List.of(ourJmeter(1, true), ourJmeter(2, true));
        OrphanJmeterReaper r = new OrphanJmeterReaper(() -> false, BASE_DIR,
                OrphanJmeterReaper.Policy.KILL, orphans::stream);

        OrphanJmeterReaper.Scan scan = r.sweep();

        assertThat(scan.found()).isEqualTo(2);
        assertThat(scan.killed()).isEqualTo(2);
        orphans.forEach(h -> verify(h).destroy());
    }
}
