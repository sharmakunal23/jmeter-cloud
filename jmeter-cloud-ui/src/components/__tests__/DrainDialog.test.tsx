import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { DrainDialog } from "../DrainDialog";
import {
  type Run,
  type ScaleDownRunResponse,
} from "../../api/runs";

vi.mock("../../api/runs", async () => {
  const actual = await vi.importActual<typeof import("../../api/runs")>("../../api/runs");
  return {
    ...actual,
    runsApi: {
      ...actual.runsApi,
      scaleDown: vi.fn(),
    },
  };
});
import { runsApi } from "../../api/runs";

const scaleDownMock = runsApi.scaleDown as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => scaleDownMock.mockReset());

function fixtureRun(): Run {
  return {
    runId: "01J0RUNAAAAAAAAAAAAAAAAAAA",
    originRegion: "us-east-1",
    testPlanBlobId: "blob-123",
    application: "checkout-svc",
    initiatedBy: "ci",
    state: "RUNNING",
    createdAt: "2026-05-15T12:00:00Z",
    fleetMembers: [],
  };
}

describe("DrainDialog — single-worker mode", () => {
  it("confirm calls scaleDown with [workerId] + invokes onSuccess + onClose", async () => {
    scaleDownMock.mockResolvedValue({
      run: fixtureRun(),
      drained: ["worker-A"],
      skipped: [],
    } satisfies ScaleDownRunResponse);

    const onClose = vi.fn();
    const onSuccess = vi.fn();
    render(
      <DrainDialog
        runId="run-1"
        workerIds={["worker-A"]}
        mode="single"
        liveWorkerCount={3}
        onClose={onClose}
        onSuccess={onSuccess}
      />,
    );

    expect(screen.getByText("worker-A")).toBeInTheDocument();
    expect(screen.getByText(/2 workers/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /^Drain worker$/i }));
    await waitFor(() => expect(scaleDownMock).toHaveBeenCalled());

    const [calledRunId, body] = scaleDownMock.mock.calls[0];
    expect(calledRunId).toBe("run-1");
    expect(body).toEqual({ workerIds: ["worker-A"] });
    expect(onSuccess).toHaveBeenCalledWith(expect.objectContaining({ runId: fixtureRun().runId }));
    expect(onClose).toHaveBeenCalled();
  });

  it("singular hint when post-drain count = 1", () => {
    render(
      <DrainDialog
        runId="run-1"
        workerIds={["worker-A"]}
        mode="single"
        liveWorkerCount={2}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    );
    expect(screen.getByText(/1 worker$/i)).toBeInTheDocument();
  });
});

describe("DrainDialog — bulk mode", () => {
  it("title + button reflect plural; submit calls scaleDown with all selected ids", async () => {
    scaleDownMock.mockResolvedValue({
      run: fixtureRun(),
      drained: ["worker-A", "worker-B", "worker-C"],
      skipped: [],
    } satisfies ScaleDownRunResponse);

    render(
      <DrainDialog
        runId="run-1"
        workerIds={["worker-A", "worker-B", "worker-C"]}
        mode="bulk"
        liveWorkerCount={5}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    );

    expect(screen.getByRole("heading", { name: /Drain 3 workers\?/ })).toBeInTheDocument();
    // Post-drain count = 5 - 3 = 2 (plural).
    expect(screen.getByText(/2 workers/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /^Drain 3 workers$/i }));
    await waitFor(() => expect(scaleDownMock).toHaveBeenCalled());

    const [, body] = scaleDownMock.mock.calls[0];
    expect(body).toEqual({ workerIds: ["worker-A", "worker-B", "worker-C"] });
  });
});

describe("DrainDialog — stopTest mode", () => {
  it("title + body reflect 'stop the test'; button labeled 'Stop test'", async () => {
    scaleDownMock.mockResolvedValue({
      run: fixtureRun(),
      drained: ["worker-A", "worker-B"],
      skipped: [],
    } satisfies ScaleDownRunResponse);

    render(
      <DrainDialog
        runId="run-1"
        workerIds={["worker-A", "worker-B"]}
        mode="stopTest"
        liveWorkerCount={2}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    );

    expect(screen.getByRole("heading", { name: /Stop the test\?/ })).toBeInTheDocument();
    expect(screen.getByText(/COMPLETED/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /^Stop test$/i }));
    await waitFor(() => expect(scaleDownMock).toHaveBeenCalled());
  });
});

describe("DrainDialog — Cancel", () => {
  it("Cancel calls onClose and does not call scaleDown", () => {
    const onClose = vi.fn();
    render(
      <DrainDialog
        runId="run-1"
        workerIds={["worker-A"]}
        mode="single"
        liveWorkerCount={2}
        onClose={onClose}
        onSuccess={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /^Cancel$/i }));
    expect(onClose).toHaveBeenCalled();
    expect(scaleDownMock).not.toHaveBeenCalled();
  });
});
