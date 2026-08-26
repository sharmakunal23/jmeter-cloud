import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";

import { RunStreamsPanel } from "../RunStreamsPanel";
import type { MemberState, RunFleetMember } from "../../api/runs";

// Mock the LogTailPanel — it owns the network polling and isn't the
// subject of these tests. The mock records its props on every render
// and a mount/unmount counter so we can assert tab switching truly
// unmounts the inactive panel (the load-bearing fleet-scale guarantee).
const lifecycle = {
  mountCount: 0,
  unmountCount: 0,
  lastProps: null as Record<string, unknown> | null,
};

interface LogTailMockProps {
  runId: string;
  workerId: string;
  streamSource: "console" | "jmeter";
  terminal?: boolean;
  showRefreshControls?: boolean;
}

function LogTailMock(props: LogTailMockProps) {
  lifecycle.lastProps = props as unknown as Record<string, unknown>;
  useEffect(() => {
    lifecycle.mountCount++;
    return () => { lifecycle.unmountCount++; };
  }, []);
  return (
    <div
      data-testid="logTailMock"
      data-streamSource={props.streamSource}
      data-workerId={props.workerId}
      data-terminal={String(props.terminal)}
      data-showrefreshcontrols={String(props.showRefreshControls)}
    >
      mocked LogTailPanel({props.workerId}/{props.streamSource})
    </div>
  );
}

vi.mock("../LogTailPanel", () => ({ LogTailPanel: LogTailMock }));

// MetricsTabPanel now does its own data fetching + uPlot
// rendering. The RunStreamsPanel tests target the surrounding shell
// (tab strip, worker selector, tab filtering, control visibility);
// the panel itself has its own dedicated test file.
vi.mock("../MetricsTabPanel", () => ({
  MetricsTabPanel: ({ runId, runState }: { runId: string; runState: string }) => (
    <div data-testid="metricsTabMock" data-runid={runId} data-runstate={runState}>
      mocked MetricsTabPanel
    </div>
  ),
}));

function makeMember(overrides: Partial<RunFleetMember> & { workerId: string; state: MemberState }): RunFleetMember {
  return {
    runId: "run-1",
    region: "local-east-1",
    fanoutStatusCode: null,
    podBaseUrl: "http://pod:8080",
    createdAt: "2026-05-10T12:00:00Z",
    ...overrides,
  };
}

const TWO_LIVE: RunFleetMember[] = [
  makeMember({ workerId: "worker-a", state: "RUNNING" }),
  makeMember({ workerId: "worker-b", state: "RUNNING" }),
];

beforeEach(() => {
  lifecycle.mountCount = 0;
  lifecycle.unmountCount = 0;
  lifecycle.lastProps = null;
  window.localStorage.clear();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("RunStreamsPanel — tab strip", () => {
  it("defaults to the Metrics tab", () => {
    render(<RunStreamsPanel runId="run-1" fleetMembers={TWO_LIVE} runState="RUNNING" />);
    expect(screen.getByRole("tab", { name: "Metrics" })).toHaveAttribute("aria-selected", "true");
    // Mocked LogTailPanel is not mounted while Metrics is active.
    expect(screen.queryByTestId("logTailMock")).toBeNull();
  });

  it("switching to Console mounts the LogTailPanel; switching back to Metrics unmounts it", () => {
    render(<RunStreamsPanel runId="run-1" fleetMembers={TWO_LIVE} runState="RUNNING" />);

    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    expect(lifecycle.mountCount).toBe(1);
    expect(lifecycle.unmountCount).toBe(0);
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-streamSource", "console");

    fireEvent.click(screen.getByRole("tab", { name: "Metrics" }));
    expect(lifecycle.unmountCount).toBe(1);
    expect(screen.queryByTestId("logTailMock")).toBeNull();
  });

  it("Console and Logs tabs request different ?stream= values", () => {
    render(<RunStreamsPanel runId="run-1" fleetMembers={TWO_LIVE} runState="RUNNING" />);

    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-streamSource", "console");

    fireEvent.click(screen.getByRole("tab", { name: "Logs" }));
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-streamSource", "jmeter");
  });

  it("supports keyboard navigation: ArrowRight cycles forward, ArrowLeft cycles back, Home/End jump", () => {
    render(<RunStreamsPanel runId="run-1" fleetMembers={TWO_LIVE} runState="RUNNING" />);
    const metrics = screen.getByRole("tab", { name: "Metrics" });
    const console_ = screen.getByRole("tab", { name: "Console" });
    const logs = screen.getByRole("tab", { name: "Logs" });

    metrics.focus();
    fireEvent.keyDown(metrics, { key: "ArrowRight" });
    expect(console_).toHaveAttribute("aria-selected", "true");

    fireEvent.keyDown(console_, { key: "ArrowRight" });
    expect(logs).toHaveAttribute("aria-selected", "true");

    fireEvent.keyDown(logs, { key: "ArrowLeft" });
    expect(console_).toHaveAttribute("aria-selected", "true");

    fireEvent.keyDown(console_, { key: "End" });
    expect(logs).toHaveAttribute("aria-selected", "true");

    fireEvent.keyDown(logs, { key: "Home" });
    expect(metrics).toHaveAttribute("aria-selected", "true");
  });

  it("persists the active tab in localStorage and restores it on remount", () => {
    const { unmount } = render(<RunStreamsPanel runId="run-1" fleetMembers={TWO_LIVE} runState="RUNNING" />);
    fireEvent.click(screen.getByRole("tab", { name: "Logs" }));
    expect(window.localStorage.getItem("jmeterCloud.runDetailTab")).toBe("logs");

    unmount();
    render(<RunStreamsPanel runId="run-1" fleetMembers={TWO_LIVE} runState="RUNNING" />);
    expect(screen.getByRole("tab", { name: "Logs" })).toHaveAttribute("aria-selected", "true");
  });
});

describe("RunStreamsPanel — worker selector", () => {
  it("defaults the selected worker to the first live (RUNNING/ACCEPTED) member", () => {
    const members: RunFleetMember[] = [
      makeMember({ workerId: "completed-1", state: "COMPLETED" }),
      makeMember({ workerId: "running-1",   state: "RUNNING" }),
      makeMember({ workerId: "running-2",   state: "RUNNING" }),
    ];
    render(<RunStreamsPanel runId="run-2" fleetMembers={members} runState="RUNNING" />);
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-workerId", "running-1");
  });

  it("falls back to the first member when no live members exist (run still in flight)", () => {
    // Run is RUNNING but every selected pod is terminal (e.g. mid-run drain
    // scenario). Console tab is still accessible because the run isn't over;
    // the worker selector should fall back to the first listed member.
    const members: RunFleetMember[] = [
      makeMember({ workerId: "completed-1", state: "COMPLETED" }),
      makeMember({ workerId: "completed-2", state: "FAILED" }),
    ];
    render(<RunStreamsPanel runId="run-3" fleetMembers={members} runState="RUNNING" />);
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-workerId", "completed-1");
  });

  it("Console and Logs tabs remember independent worker selections via localStorage", () => {
    render(<RunStreamsPanel runId="run-4" fleetMembers={TWO_LIVE} runState="RUNNING" />);

    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    fireEvent.change(screen.getByLabelText(/^Worker/), { target: { value: "worker-b" } });
    expect(window.localStorage.getItem("jmeterCloud.runStreams.console.worker.run-4")).toBe("worker-b");

    fireEvent.click(screen.getByRole("tab", { name: "Logs" }));
    // Logs tab gets its own default (first live) — independent of console's choice.
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-workerId", "worker-a");
    fireEvent.change(screen.getByLabelText(/^Worker/), { target: { value: "worker-b" } });
    expect(window.localStorage.getItem("jmeterCloud.runStreams.logs.worker.run-4")).toBe("worker-b");

    // Switch back to Console — should still be on worker-b (its own remembered choice).
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-workerId", "worker-b");
  });

  it("falls back to the default when the stored workerId no longer exists in the fleet", () => {
    window.localStorage.setItem("jmeterCloud.runStreams.console.worker.run-5", "worker-vanished");
    render(<RunStreamsPanel runId="run-5" fleetMembers={TWO_LIVE} runState="RUNNING" />);
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-workerId", "worker-a");
  });
});

describe("RunStreamsPanel — terminal awareness (user ask, 2026-05-10)", () => {
  it("when the whole run is COMPLETED, Console + Logs tabs are hidden entirely (pod likely recycled for next run)", () => {
    // Stronger guarantee than the prior "passes terminal=true" check — the
    // tabs themselves disappear so the operator can't even ask for stale
    // / wrong-run content. The pod is single-tenant and will be running
    // another test by now.
    render(<RunStreamsPanel runId="run-6" fleetMembers={TWO_LIVE} runState="COMPLETED" />);
    expect(screen.getByRole("tab", { name: "Metrics" })).toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Console" })).toBeNull();
    expect(screen.queryByRole("tab", { name: "Logs" })).toBeNull();
  });

  it("passes terminal=true for a member-terminal pod even while the run is still RUNNING (Step 32 mid-run drain)", () => {
    const members: RunFleetMember[] = [
      makeMember({ workerId: "completed-1", state: "COMPLETED" }), // drained mid-run
      makeMember({ workerId: "running-1",   state: "RUNNING" }),
    ];
    render(<RunStreamsPanel runId="run-7" fleetMembers={members} runState="RUNNING" />);
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));

    // Default selects the live one — terminal=false.
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-workerId", "running-1");
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-terminal", "false");

    // Switch to the drained pod — terminal=true even though run is RUNNING.
    fireEvent.change(screen.getByLabelText(/^Worker/), { target: { value: "completed-1" } });
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-terminal", "true");
  });

  it("annotates terminal pods in the worker dropdown so the operator sees per-node status at a glance", () => {
    const members: RunFleetMember[] = [
      makeMember({ workerId: "running-1",   state: "RUNNING" }),
      makeMember({ workerId: "completed-1", state: "COMPLETED" }),
      makeMember({ workerId: "failed-1",    state: "FAILED" }),
    ];
    render(<RunStreamsPanel runId="run-8" fleetMembers={members} runState="RUNNING" />);
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));

    const select = screen.getByLabelText(/^Worker/) as HTMLSelectElement;
    const options = within(select).getAllByRole("option");
    const labels = options.map((o) => o.textContent ?? "");
    expect(labels[0]).toContain("RUNNING");
    expect(labels[0]).not.toContain("(terminal)");
    expect(labels[1]).toContain("COMPLETED");
    expect(labels[1]).toContain("(terminal)");
    expect(labels[2]).toContain("FAILED");
    expect(labels[2]).toContain("(terminal)");
  });

  it("renders a live/terminal/pending counts summary so fleet status is visible at a glance", () => {
    const members: RunFleetMember[] = [
      makeMember({ workerId: "r1", state: "RUNNING" }),
      makeMember({ workerId: "r2", state: "RUNNING" }),
      makeMember({ workerId: "c1", state: "COMPLETED" }),
      makeMember({ workerId: "p1", state: "PENDING" }),
    ];
    render(<RunStreamsPanel runId="run-9" fleetMembers={members} runState="RUNNING" />);
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    expect(screen.getByText(/2 live · 1 completed\/failed · 1 pending/)).toBeInTheDocument();
  });
});

describe("RunStreamsPanel — terminal-run tab filtering (user ask, 2026-05-10)", () => {
  it.each([
    ["FAILED",   "Run failed"],
    ["ABORTED",  "Run aborted"],
  ])("hides Console + Logs tabs when run state is %s", (state) => {
    render(<RunStreamsPanel runId="run-x" fleetMembers={TWO_LIVE} runState={state as "FAILED" | "ABORTED"} />);
    expect(screen.getByRole("tab", { name: "Metrics" })).toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Console" })).toBeNull();
    expect(screen.queryByRole("tab", { name: "Logs" })).toBeNull();
  });

  it("auto-snaps active tab back to Metrics when the run transitions to terminal mid-session", () => {
    const { rerender } = render(
      <RunStreamsPanel runId="run-tx" fleetMembers={TWO_LIVE} runState="RUNNING" />,
    );
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    expect(screen.getByRole("tab", { name: "Console" })).toHaveAttribute("aria-selected", "true");

    // Run transitions to COMPLETED — Console tab disappears, Metrics
    // becomes the (only) active tab automatically.
    rerender(<RunStreamsPanel runId="run-tx" fleetMembers={TWO_LIVE} runState="COMPLETED" />);
    expect(screen.queryByRole("tab", { name: "Console" })).toBeNull();
    expect(screen.getByRole("tab", { name: "Metrics" })).toHaveAttribute("aria-selected", "true");
  });

  it("respects per-tab keyboard nav when only Metrics is shown (single-tab loop)", () => {
    render(<RunStreamsPanel runId="run-sk" fleetMembers={TWO_LIVE} runState="COMPLETED" />);
    const metrics = screen.getByRole("tab", { name: "Metrics" });
    metrics.focus();
    fireEvent.keyDown(metrics, { key: "ArrowRight" });
    // Single tab => arrow nav is a no-op (loops to itself).
    expect(metrics).toHaveAttribute("aria-selected", "true");
  });
});

describe("RunStreamsPanel — Pause/Refresh control visibility (user ask, 2026-05-10)", () => {
  it("passes showRefreshControls=true when the selected member is RUNNING", () => {
    render(<RunStreamsPanel runId="run-r1" fleetMembers={TWO_LIVE} runState="RUNNING" />);
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-showrefreshcontrols", "true");
  });

  it.each([
    "PENDING",
    "REQUESTED",
    "ACCEPTED",
    "COMPLETED",
    "FAILED",
    "ABORTED",
  ])("passes showRefreshControls=false when the selected member is %s (run still RUNNING)", (state) => {
    const members: RunFleetMember[] = [
      makeMember({ workerId: "the-pod", state: state as MemberState }),
      makeMember({ workerId: "running-pod", state: "RUNNING" }), // ensures Console tab is enabled
    ];
    render(<RunStreamsPanel runId={`run-r-${state}`} fleetMembers={members} runState="RUNNING" />);
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    fireEvent.change(screen.getByLabelText(/^Worker/), { target: { value: "the-pod" } });
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-showrefreshcontrols", "false");
  });
});

describe("RunStreamsPanel — empty fleet", () => {
  it("renders a friendly hint when no members exist yet (e.g. PREPARING)", () => {
    render(<RunStreamsPanel runId="run-10" fleetMembers={[]} runState="PREPARING" />);
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    expect(screen.getByText(/No fleet members yet/)).toBeInTheDocument();
    expect(screen.queryByTestId("logTailMock")).toBeNull();
  });
});

describe("RunStreamsPanel — fleet-scale safety guarantee", () => {
  it("never mounts more than one LogTailPanel at a time, no matter how many members exist", () => {
    const members: RunFleetMember[] = Array.from({ length: 100 }, (_, i) =>
      makeMember({ workerId: `worker-${i}`, state: "RUNNING" }),
    );
    render(<RunStreamsPanel runId="run-fleet" fleetMembers={members} runState="RUNNING" />);

    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    expect(screen.getAllByTestId("logTailMock")).toHaveLength(1);

    // Cycling the worker through several pods should trigger one mount + one
    // unmount per change — never an accumulation.
    const startMount = lifecycle.mountCount;
    const startUnmount = lifecycle.unmountCount;
    for (const target of ["worker-37", "worker-99", "worker-0"]) {
      act(() => {
        fireEvent.change(screen.getByLabelText(/^Worker/), { target: { value: target } });
      });
    }
    expect(lifecycle.mountCount - startMount).toBe(3);
    expect(lifecycle.unmountCount - startUnmount).toBe(3);
    expect(screen.getAllByTestId("logTailMock")).toHaveLength(1);
  });

  it("worker dropdown lists all 100 members and defaults to the first live one", () => {
    const members: RunFleetMember[] = Array.from({ length: 100 }, (_, i) =>
      makeMember({
        workerId: `worker-${i}`,
        // First five completed (terminal) — default selection should skip them.
        state: i < 5 ? "COMPLETED" : "RUNNING",
      }),
    );
    render(<RunStreamsPanel runId="run-fleet-default" fleetMembers={members} runState="RUNNING" />);
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));

    const select = screen.getByLabelText(/^Worker/) as HTMLSelectElement;
    expect(within(select).getAllByRole("option")).toHaveLength(100);
    // Default = first live (worker-5), not the first terminal one.
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-workerId", "worker-5");
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-terminal", "false");
  });
});
