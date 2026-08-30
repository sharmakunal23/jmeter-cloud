import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";

import { StreamTabPanel } from "../StreamTabPanel";
import type { MemberState, RunFleetMember } from "../../api/runs";

const lifecycle = { mountCount: 0, unmountCount: 0 };

function LogTailMock(props: { runId: string; workerId: string; streamSource: "console" | "jmeter"; terminal?: boolean; showRefreshControls?: boolean }) {
  useEffect(() => {
    lifecycle.mountCount++;
    return () => { lifecycle.unmountCount++; };
  }, []);
  return (
    <div data-testid="logTailMock" data-streamSource={props.streamSource} data-workerId={props.workerId}
      data-terminal={String(props.terminal)} data-showrefreshcontrols={String(props.showRefreshControls)}>
      mocked LogTailPanel({props.workerId}/{props.streamSource})
    </div>
  );
}
vi.mock("../LogTailPanel", () => ({ LogTailPanel: LogTailMock }));

function makeMember(overrides: Partial<RunFleetMember> & { workerId: string; state: MemberState }): RunFleetMember {
  return { runId: "run-1", region: "local-east-1", fanoutStatusCode: null, podBaseUrl: "http://pod:8080",
    createdAt: "2026-05-10T12:00:00Z", ...overrides };
}
const TWO_LIVE: RunFleetMember[] = [
  makeMember({ workerId: "worker-a", state: "RUNNING" }),
  makeMember({ workerId: "worker-b", state: "RUNNING" }),
];
const HINT = "No fleet members yet — the run hasn't been fanned out to any workers.";

function renderConsole(runId: string, members: RunFleetMember[], runTerminal = false, panelKey: "console" | "logs" = "console") {
  return render(
    <StreamTabPanel runId={runId} panelKey={panelKey} streamSource={panelKey === "console" ? "console" : "jmeter"}
      fleetMembers={members} runTerminal={runTerminal} emptyHint={HINT} />,
  );
}

beforeEach(() => { lifecycle.mountCount = 0; lifecycle.unmountCount = 0; window.localStorage.clear(); });
afterEach(() => { vi.clearAllMocks(); });

describe("StreamTabPanel — worker selector", () => {
  it("defaults the selected worker to the first live (RUNNING/ACCEPTED) member", () => {
    renderConsole("run-2", [
      makeMember({ workerId: "completed-1", state: "COMPLETED" }),
      makeMember({ workerId: "running-1",   state: "RUNNING" }),
      makeMember({ workerId: "running-2",   state: "RUNNING" }),
    ]);
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-workerId", "running-1");
  });

  it("falls back to the first member when no live members exist (run still in flight)", () => {
    renderConsole("run-3", [
      makeMember({ workerId: "completed-1", state: "COMPLETED" }),
      makeMember({ workerId: "completed-2", state: "FAILED" }),
    ]);
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-workerId", "completed-1");
  });

  it("Console and Logs remember independent worker selections via localStorage", () => {
    const console_ = renderConsole("run-4", TWO_LIVE);
    fireEvent.change(screen.getByLabelText(/^Worker/), { target: { value: "worker-b" } });
    expect(window.localStorage.getItem("jmeterCloud.runStreams.console.worker.run-4")).toBe("worker-b");
    console_.unmount();

    const logs = renderConsole("run-4", TWO_LIVE, false, "logs");
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-workerId", "worker-a");
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-streamSource", "jmeter");
    fireEvent.change(screen.getByLabelText(/^Worker/), { target: { value: "worker-b" } });
    expect(window.localStorage.getItem("jmeterCloud.runStreams.logs.worker.run-4")).toBe("worker-b");
    logs.unmount();

    renderConsole("run-4", TWO_LIVE);
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-workerId", "worker-b");
  });

  it("falls back to the default when the stored workerId no longer exists in the fleet", () => {
    window.localStorage.setItem("jmeterCloud.runStreams.console.worker.run-5", "worker-vanished");
    renderConsole("run-5", TWO_LIVE);
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-workerId", "worker-a");
  });
});

describe("StreamTabPanel — terminal awareness", () => {
  it("passes terminal=true for a member-terminal pod even while the run is still RUNNING (mid-run drain)", () => {
    renderConsole("run-7", [
      makeMember({ workerId: "completed-1", state: "COMPLETED" }),
      makeMember({ workerId: "running-1",   state: "RUNNING" }),
    ]);
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-workerId", "running-1");
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-terminal", "false");
    fireEvent.change(screen.getByLabelText(/^Worker/), { target: { value: "completed-1" } });
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-terminal", "true");
  });

  it("annotates terminal pods in the worker dropdown", () => {
    renderConsole("run-8", [
      makeMember({ workerId: "running-1",   state: "RUNNING" }),
      makeMember({ workerId: "completed-1", state: "COMPLETED" }),
      makeMember({ workerId: "failed-1",    state: "FAILED" }),
    ]);
    const labels = within(screen.getByLabelText(/^Worker/)).getAllByRole("option").map((o) => o.textContent ?? "");
    expect(labels[0]).toContain("RUNNING");
    expect(labels[0]).not.toContain("(terminal)");
    expect(labels[1]).toContain("COMPLETED (terminal)");
    expect(labels[2]).toContain("FAILED (terminal)");
  });

  it("renders a live/terminal/pending counts summary", () => {
    renderConsole("run-9", [
      makeMember({ workerId: "r1", state: "RUNNING" }),
      makeMember({ workerId: "r2", state: "RUNNING" }),
      makeMember({ workerId: "c1", state: "COMPLETED" }),
      makeMember({ workerId: "p1", state: "PENDING" }),
    ]);
    expect(screen.getByText(/2 live · 1 completed\/failed · 1 pending/)).toBeInTheDocument();
  });
});

describe("StreamTabPanel — Pause/Refresh control visibility", () => {
  it("passes showRefreshControls=true when the selected member is RUNNING", () => {
    renderConsole("run-r1", TWO_LIVE);
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-showrefreshcontrols", "true");
  });

  it.each(["PENDING", "REQUESTED", "ACCEPTED", "COMPLETED", "FAILED", "ABORTED"])(
    "passes showRefreshControls=false when the selected member is %s (run still RUNNING)", (state) => {
      renderConsole(`run-r-${state}`, [
        makeMember({ workerId: "the-pod", state: state as MemberState }),
        makeMember({ workerId: "running-pod", state: "RUNNING" }),
      ]);
      fireEvent.change(screen.getByLabelText(/^Worker/), { target: { value: "the-pod" } });
      expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-showrefreshcontrols", "false");
    });
});

describe("StreamTabPanel — empty fleet and fleet scale", () => {
  it("renders the hint when no members exist yet (e.g. PREPARING)", () => {
    renderConsole("run-10", []);
    expect(screen.getByText(/No fleet members yet/)).toBeInTheDocument();
    expect(screen.queryByTestId("logTailMock")).toBeNull();
  });

  it("never mounts more than one LogTailPanel at a time, no matter how many members exist", () => {
    const members = Array.from({ length: 100 }, (_, i) => makeMember({ workerId: `worker-${i}`, state: i < 5 ? "COMPLETED" : "RUNNING" }));
    renderConsole("run-fleet", members);
    expect(screen.getAllByTestId("logTailMock")).toHaveLength(1);
    expect(within(screen.getByLabelText(/^Worker/)).getAllByRole("option")).toHaveLength(100);
    // Default = first live (worker-5), not the first terminal one.
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-workerId", "worker-5");
    const startMount = lifecycle.mountCount;
    const startUnmount = lifecycle.unmountCount;
    for (const target of ["worker-37", "worker-99", "worker-0"]) {
      act(() => { fireEvent.change(screen.getByLabelText(/^Worker/), { target: { value: target } }); });
    }
    expect(lifecycle.mountCount - startMount).toBe(3);
    expect(lifecycle.unmountCount - startUnmount).toBe(3);
    expect(screen.getAllByTestId("logTailMock")).toHaveLength(1);
  });
});
