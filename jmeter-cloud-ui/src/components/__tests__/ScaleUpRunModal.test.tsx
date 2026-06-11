import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { ScaleUpRunModal } from "../ScaleUpRunModal";
import {
  GlobalOrchestratorError,
  type Run,
  type RunFleetMember,
  type ScaleUpRunResponse,
} from "../../api/runs";
import type { RegionCapacity } from "../../api/regions";

vi.mock("../../api/regions", () => ({
  regionsApi: { list: vi.fn() },
}));
vi.mock("../../api/applications", () => ({
  applicationsApi: { list: vi.fn() },
}));
vi.mock("../../api/runs", async () => {
  const actual = await vi.importActual<typeof import("../../api/runs")>("../../api/runs");
  return {
    ...actual,
    runsApi: {
      ...actual.runsApi,
      scaleUp: vi.fn(),
      status: vi.fn(),
      get: vi.fn(),
    },
  };
});
import { regionsApi } from "../../api/regions";
import { applicationsApi } from "../../api/applications";
import { runsApi } from "../../api/runs";

const regionsMock = regionsApi.list as unknown as ReturnType<typeof vi.fn>;
const appsMock = applicationsApi.list as unknown as ReturnType<typeof vi.fn>;
const scaleUpMock = runsApi.scaleUp as unknown as ReturnType<typeof vi.fn>;
const statusMock = runsApi.status as unknown as ReturnType<typeof vi.fn>;
const getMock = runsApi.get as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  regionsMock.mockReset();
  appsMock.mockReset();
  scaleUpMock.mockReset();
  statusMock.mockReset();
  getMock.mockReset();
  regionsMock.mockResolvedValue(fixtureRegions());
  appsMock.mockResolvedValue([appWithCapacity()]);
  // Default: no new members to wait on (resp has empty fleet) → poll skipped.
  statusMock.mockResolvedValue({ runId: "run-1", state: "RUNNING", members: [] });
  getMock.mockResolvedValue(fixtureRun());
});

function fixtureRegions(): RegionCapacity[] {
  return [
    { region: "local-east-1", totalPods: 20, idlePods: 10, lostPods: 0 },
    { region: "local-west-2", totalPods: 20, idlePods: 10, lostPods: 0 },
  ];
}

/** Application fixture matching propRun()'s `application` ("checkout-svc"). */
function appWithCapacity(
  capacity: Array<{ region: string; maxAvailable: number }> = [
    { region: "local-east-1", maxAvailable: 10 },
    { region: "local-west-2", maxAvailable: 10 },
  ],
) {
  return {
    applicationId: "app-checkout",
    name: "checkout-svc",
    healthEndpoints: [],
    capacity,
    createdAt: "2026-05-15T12:00:00Z",
  };
}

/** The scaleUp RESPONSE run (post-scale snapshot). Empty fleet → poll skipped. */
function fixtureRun(members: RunFleetMember[] = []): Run {
  return {
    runId: "run-1",
    originRegion: "us-east-1",
    testPlanBlobId: "blob-123",
    application: "checkout-svc",
    initiatedBy: "ci",
    state: "RUNNING",
    createdAt: "2026-05-15T12:00:00Z",
    fleetMembers: members,
  };
}

function member(
  workerId: string,
  properties: Record<string, string> = {},
  overrides: Partial<RunFleetMember> = {},
): RunFleetMember {
  return {
    runId: "run-1",
    workerId,
    region: "local-east-1",
    state: "RUNNING",
    createdAt: "2026-05-15T12:00:00Z",
    joinedAtSecond: null,
    properties,
    ...overrides,
  };
}

/** The `run` PROP — the currently-open run the modal scales up. */
function propRun(overrides: Partial<Run> = {}): Run {
  return {
    runId: "run-1",
    originRegion: "us-east-1",
    testPlanBlobId: "blob-123",
    application: "checkout-svc",
    initiatedBy: "ci",
    state: "RUNNING",
    createdAt: "2026-05-15T12:00:00Z",
    fleetMembers: [],
    ...overrides,
  };
}

describe("ScaleUpRunModal — happy path", () => {
  it("submits the picked allocations and (after the staged flow) calls onSuccess + onClose", async () => {
    scaleUpMock.mockResolvedValue({
      run: fixtureRun(),
      requested: 2,
      granted: 2,
      partial: false,
    } satisfies ScaleUpRunResponse);

    const onClose = vi.fn();
    const onSuccess = vi.fn();
    render(<ScaleUpRunModal run={propRun()} onClose={onClose} onSuccess={onSuccess} />);

    await waitFor(() => expect(regionsMock).toHaveBeenCalled());
    const addEastButton = await screen.findByRole("button", {
      name: /add workers to local-east-1/i,
    });
    fireEvent.click(addEastButton);
    fireEvent.click(addEastButton);

    fireEvent.click(screen.getByRole("button", { name: /Add 2 workers/i }));
    await waitFor(() => expect(scaleUpMock).toHaveBeenCalled());

    const [calledRunId, body, opts] = scaleUpMock.mock.calls[0];
    expect(calledRunId).toBe("run-1");
    expect(body.allocations).toEqual([{ region: "local-east-1", count: 2 }]);
    expect(opts?.bestEffort).toBeFalsy();
    expect(opts?.spinShortfall).toBeFalsy();

    // The staged flow runs (poll skipped — empty resp fleet), then resolves.
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(onSuccess).toHaveBeenCalled();
  });
});

describe("ScaleUpRunModal — capacity-aware ceiling (problem #1)", () => {
  it("clamps the ceiling at maxAvailable − this run's active workers", async () => {
    // 2 workers already RUNNING in local-east-1, max is 5 → can add only 3.
    appsMock.mockResolvedValue([appWithCapacity([{ region: "local-east-1", maxAvailable: 5 }])]);
    const run = propRun({
      fleetMembers: [member("w1"), member("w2")],
    });
    render(<ScaleUpRunModal run={run} onClose={vi.fn()} onSuccess={vi.fn()} />);
    await waitFor(() => expect(appsMock).toHaveBeenCalled());

    const countInput = await screen.findByLabelText(/number of workers to add to local-east-1/i);
    // Ask for 5; the cap-aware ceiling (5 − 2 = 3) clamps it to 3.
    fireEvent.change(countInput, { target: { value: "5" } });
    fireEvent.click(screen.getByRole("button", { name: /add workers to local-east-1/i }));
    expect(await screen.findByRole("button", { name: /Add 3 workers/i })).toBeInTheDocument();
  });

  it("with no active workers the ceiling is the full maxAvailable", async () => {
    appsMock.mockResolvedValue([appWithCapacity([{ region: "local-east-1", maxAvailable: 5 }])]);
    // Only 2 IDLE pods, but max is 5 and no active workers → can pick 5.
    regionsMock.mockResolvedValue([{ region: "local-east-1", totalPods: 5, idlePods: 2, lostPods: 0 }]);

    render(<ScaleUpRunModal run={propRun()} onClose={vi.fn()} onSuccess={vi.fn()} />);
    await waitFor(() => expect(appsMock).toHaveBeenCalled());
    const countInput = await screen.findByLabelText(/number of workers to add to local-east-1/i);
    fireEvent.change(countInput, { target: { value: "5" } });
    fireEvent.click(screen.getByRole("button", { name: /add workers to local-east-1/i }));
    expect(await screen.findByRole("button", { name: /Add 5 workers/i })).toBeInTheDocument();
  });
});

describe("ScaleUpRunModal — staged progress modal (problem #2)", () => {
  it("submitting swaps the form for the staged progress modal (Provisioning → Distributing → …)", async () => {
    // Never-resolving scaleUp keeps the staged modal up so we can assert it.
    scaleUpMock.mockReturnValue(new Promise(() => {}));

    render(<ScaleUpRunModal run={propRun()} onClose={vi.fn()} onSuccess={vi.fn()} />);
    await waitFor(() => expect(regionsMock).toHaveBeenCalled());
    const addEastButton = await screen.findByRole("button", {
      name: /add workers to local-east-1/i,
    });
    fireEvent.click(addEastButton);
    fireEvent.click(screen.getByRole("button", { name: /Add 1 worker/i }));

    // Same staged modal the launcher shows.
    expect(await screen.findByText(/Provisioning workers/i)).toBeInTheDocument();
    expect(screen.getByText(/Distributing test plan/i)).toBeInTheDocument();
    expect(screen.getByText(/Starting JMeter/i)).toBeInTheDocument();
    expect(screen.getByText(/Verifying new workers/i)).toBeInTheDocument();
    // The form is gone.
    expect(screen.queryByRole("button", { name: /add workers to local-east-1/i })).not.toBeInTheDocument();
  });

  it("waits for the new workers to reach RUNNING before closing", async () => {
    scaleUpMock.mockResolvedValue({
      run: fixtureRun([member("w-new", {}, { joinedAtSecond: 12 })]),
      requested: 1, granted: 1, partial: false,
    } satisfies ScaleUpRunResponse);
    // New worker comes up RUNNING on the first poll.
    statusMock.mockResolvedValue({
      runId: "run-1", state: "RUNNING",
      members: [member("w-new", {}, { joinedAtSecond: 12, state: "RUNNING" })],
    });

    const onClose = vi.fn();
    const onSuccess = vi.fn();
    render(<ScaleUpRunModal run={propRun()} onClose={onClose} onSuccess={onSuccess} />);
    await waitFor(() => expect(regionsMock).toHaveBeenCalled());
    fireEvent.click(await screen.findByRole("button", { name: /add workers to local-east-1/i }));
    fireEvent.click(screen.getByRole("button", { name: /Add 1 worker/i }));

    await waitFor(() => expect(statusMock).toHaveBeenCalled());
    await waitFor(() => expect(onClose).toHaveBeenCalled(), { timeout: 4000 });
    expect(onSuccess).toHaveBeenCalled();
  });
});

describe("ScaleUpRunModal — INSUFFICIENT_CAPACITY recovery prompt", () => {
  it("shows the 'Workers not ready' prompt; 'Provision …' retries with spinShortfall=true", async () => {
    scaleUpMock.mockRejectedValueOnce(
      new GlobalOrchestratorError(503, "INSUFFICIENT_CAPACITY",
        "local-east-1: requested 5, claimed 2",
        { shortfall: [{ region: "local-east-1", requested: 5, claimed: 2 }] }),
    );
    scaleUpMock.mockResolvedValueOnce({
      run: fixtureRun(), requested: 5, granted: 5, partial: false,
    } satisfies ScaleUpRunResponse);

    const onClose = vi.fn();
    const onSuccess = vi.fn();
    render(<ScaleUpRunModal run={propRun()} onClose={onClose} onSuccess={onSuccess} />);

    await waitFor(() => expect(regionsMock).toHaveBeenCalled());
    const addEastButton = await screen.findByRole("button", {
      name: /add workers to local-east-1/i,
    });
    for (let i = 0; i < 5; i++) fireEvent.click(addEastButton);
    fireEvent.click(screen.getByRole("button", { name: /Add 5 workers/i }));
    await waitFor(() => expect(scaleUpMock).toHaveBeenCalledTimes(1));

    expect(await screen.findByText(/Workers not ready/i)).toBeInTheDocument();
    // Gap = 5 − 2 = 3.
    const provisionButton = screen.getByRole("button", { name: /Provision 3 missing workers and add/i });
    fireEvent.click(provisionButton);

    await waitFor(() => expect(scaleUpMock).toHaveBeenCalledTimes(2));
    expect(scaleUpMock.mock.calls[1][2]).toEqual(expect.objectContaining({ spinShortfall: true }));
    await waitFor(() => expect(onSuccess).toHaveBeenCalled(), { timeout: 4000 });
  });

  it("'Add the workers that are ready' retries with bestEffort=true", async () => {
    scaleUpMock.mockRejectedValueOnce(
      new GlobalOrchestratorError(503, "INSUFFICIENT_CAPACITY",
        "local-east-1: requested 5, claimed 3",
        { shortfall: [{ region: "local-east-1", requested: 5, claimed: 3 }] }),
    );
    scaleUpMock.mockResolvedValueOnce({
      run: fixtureRun(), requested: 5, granted: 3, partial: true,
    } satisfies ScaleUpRunResponse);

    render(<ScaleUpRunModal run={propRun()} onClose={vi.fn()} onSuccess={vi.fn()} />);
    await waitFor(() => expect(regionsMock).toHaveBeenCalled());
    const addEastButton = await screen.findByRole("button", {
      name: /add workers to local-east-1/i,
    });
    for (let i = 0; i < 5; i++) fireEvent.click(addEastButton);
    fireEvent.click(screen.getByRole("button", { name: /Add 5 workers/i }));
    await waitFor(() => expect(scaleUpMock).toHaveBeenCalledTimes(1));

    fireEvent.click(await screen.findByRole("button", { name: /Add the workers that are ready/i }));
    await waitFor(() => expect(scaleUpMock).toHaveBeenCalledTimes(2));
    expect(scaleUpMock.mock.calls[1][2]).toEqual(expect.objectContaining({ bestEffort: true }));
  });
});

describe("ScaleUpRunModal — predicted shortfall skips the doomed strict POST", () => {
  it("when the pick exceeds idle pods, shows the prompt WITHOUT calling scaleUp; Provision fires exactly once", async () => {
    // Headroom allows 5 (max 10, no active workers) but only 2 pods are idle.
    appsMock.mockResolvedValue([appWithCapacity([{ region: "local-east-1", maxAvailable: 10 }])]);
    regionsMock.mockResolvedValue([{ region: "local-east-1", totalPods: 10, idlePods: 2, lostPods: 0 }]);
    scaleUpMock.mockResolvedValue({
      run: fixtureRun(), requested: 5, granted: 5, partial: false,
    } satisfies ScaleUpRunResponse);

    const onSuccess = vi.fn();
    render(<ScaleUpRunModal run={propRun()} onClose={vi.fn()} onSuccess={onSuccess} />);
    await waitFor(() => expect(regionsMock).toHaveBeenCalled());

    const countInput = await screen.findByLabelText(/number of workers to add to local-east-1/i);
    fireEvent.change(countInput, { target: { value: "5" } });
    fireEvent.click(screen.getByRole("button", { name: /add workers to local-east-1/i }));
    fireEvent.click(await screen.findByRole("button", { name: /Add 5 workers/i }));

    // Prompt shows immediately — the gap is 5 − 2 = 3 — and NO POST went out.
    expect(await screen.findByText(/Workers not ready/i)).toBeInTheDocument();
    expect(scaleUpMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: /Provision 3 missing workers and add/i }));
    await waitFor(() => expect(scaleUpMock).toHaveBeenCalledTimes(1));
    expect(scaleUpMock.mock.calls[0][2]).toEqual(expect.objectContaining({ spinShortfall: true }));
    await waitFor(() => expect(onSuccess).toHaveBeenCalled(), { timeout: 4000 });
  });

  it("when idle pods cover the pick, submits a single strict scaleUp (no prompt)", async () => {
    // 10 idle pods (default fixture) easily cover a pick of 2.
    scaleUpMock.mockResolvedValue({
      run: fixtureRun(), requested: 2, granted: 2, partial: false,
    } satisfies ScaleUpRunResponse);

    render(<ScaleUpRunModal run={propRun()} onClose={vi.fn()} onSuccess={vi.fn()} />);
    await waitFor(() => expect(regionsMock).toHaveBeenCalled());
    const addEast = await screen.findByRole("button", { name: /add workers to local-east-1/i });
    fireEvent.click(addEast);
    fireEvent.click(addEast);
    fireEvent.click(screen.getByRole("button", { name: /Add 2 workers/i }));

    await waitFor(() => expect(scaleUpMock).toHaveBeenCalledTimes(1));
    expect(scaleUpMock.mock.calls[0][2]?.spinShortfall).toBeFalsy();
    expect(scaleUpMock.mock.calls[0][2]?.bestEffort).toBeFalsy();
    expect(screen.queryByText(/Workers not ready/i)).not.toBeInTheDocument();
  });
});

describe("ScaleUpRunModal — pre-condition errors", () => {
  it("RUN_NOT_SCALABLE surfaces a failed stage with the error message", async () => {
    scaleUpMock.mockRejectedValue(
      new GlobalOrchestratorError(409, "RUN_NOT_SCALABLE",
        "run is in state COMPLETED; scaleUp requires state RUNNING"),
    );

    render(<ScaleUpRunModal run={propRun()} onClose={vi.fn()} onSuccess={vi.fn()} />);
    await waitFor(() => expect(regionsMock).toHaveBeenCalled());
    fireEvent.click(await screen.findByRole("button", { name: /add workers to local-east-1/i }));
    fireEvent.click(screen.getByRole("button", { name: /Add 1 worker/i }));

    expect(await screen.findByText(/scaleUp requires state RUNNING/i)).toBeInTheDocument();
    // Not a capacity shortfall → no provisioning prompt.
    expect(screen.queryByText(/Workers not ready/i)).not.toBeInTheDocument();
  });

  it("Add button is disabled until the operator picks at least one worker", async () => {
    render(<ScaleUpRunModal run={propRun()} onClose={vi.fn()} onSuccess={vi.fn()} />);
    await waitFor(() => expect(regionsMock).toHaveBeenCalled());
    expect(screen.getByRole("button", { name: /^Add workers$/i })).toBeDisabled();
  });
});

describe("ScaleUpRunModal — global properties + inherited save-results", () => {
  it("pre-fills global properties from the existing fleet and applies them to new workers", async () => {
    scaleUpMock.mockResolvedValue({
      run: fixtureRun(), requested: 1, granted: 1, partial: false,
    } satisfies ScaleUpRunResponse);

    const run = propRun({
      fleetMembers: [
        member("w1", { USER_OFFSET: "5", THREADS: "10" }),
        member("w2", { USER_OFFSET: "5", THREADS: "10" }),
      ],
    });
    render(<ScaleUpRunModal run={run} onClose={vi.fn()} onSuccess={vi.fn()} />);
    await waitFor(() => expect(regionsMock).toHaveBeenCalled());

    expect(screen.getByDisplayValue("USER_OFFSET")).toBeInTheDocument();
    expect(screen.getByDisplayValue("THREADS")).toBeInTheDocument();

    const addEast = await screen.findByRole("button", { name: /add workers to local-east-1/i });
    fireEvent.click(addEast);
    fireEvent.click(screen.getByRole("button", { name: /Add 1 worker/i }));
    await waitFor(() => expect(scaleUpMock).toHaveBeenCalled());

    const [, body] = scaleUpMock.mock.calls[0];
    expect(body.allocations[0].perNodeProperties).toEqual([
      { USER_OFFSET: "5", THREADS: "10" },
    ]);
  });

  it("edits to the globals apply only to the new workers (sent as perNodeProperties)", async () => {
    scaleUpMock.mockResolvedValue({
      run: fixtureRun(), requested: 1, granted: 1, partial: false,
    } satisfies ScaleUpRunResponse);

    const run = propRun({ fleetMembers: [member("w1", { USER_OFFSET: "5" })] });
    render(<ScaleUpRunModal run={run} onClose={vi.fn()} onSuccess={vi.fn()} />);
    await waitFor(() => expect(regionsMock).toHaveBeenCalled());

    fireEvent.change(screen.getByDisplayValue("5"), { target: { value: "7" } });

    const addEast = await screen.findByRole("button", { name: /add workers to local-east-1/i });
    fireEvent.click(addEast);
    fireEvent.click(screen.getByRole("button", { name: /Add 1 worker/i }));
    await waitFor(() => expect(scaleUpMock).toHaveBeenCalled());

    const [, body] = scaleUpMock.mock.calls[0];
    expect(body.allocations[0].perNodeProperties).toEqual([{ USER_OFFSET: "7" }]);
  });

  it("surfaces the inherited saveResults setting read-only (On / Off)", async () => {
    const { unmount } = render(
      <ScaleUpRunModal run={propRun({ saveResults: true })} onClose={vi.fn()} onSuccess={vi.fn()} />,
    );
    await waitFor(() => expect(regionsMock).toHaveBeenCalled());
    expect(screen.getByText(/Save results:/i)).toBeInTheDocument();
    expect(screen.getByText("On")).toBeInTheDocument();
    expect(screen.getByText(/new workers will upload their JTLs too/i)).toBeInTheDocument();
    unmount();

    render(
      <ScaleUpRunModal run={propRun({ saveResults: false })} onClose={vi.fn()} onSuccess={vi.fn()} />,
    );
    await waitFor(() => expect(regionsMock).toHaveBeenCalledTimes(2));
    expect(screen.getByText("Off")).toBeInTheDocument();
    expect(screen.getByText(/won't upload results/i)).toBeInTheDocument();
  });
});
