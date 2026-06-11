import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { AbortRunDialog } from "../AbortRunDialog";
import { GlobalOrchestratorError, type Run } from "../../api/runs";

vi.mock("../../api/runs", async () => {
  const actual = await vi.importActual<typeof import("../../api/runs")>("../../api/runs");
  return {
    ...actual,
    runsApi: {
      ...actual.runsApi,
      abort: vi.fn(),
    },
  };
});
import { runsApi } from "../../api/runs";

const abortMock = runsApi.abort as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => abortMock.mockReset());

function abortedRun(): Run {
  return {
    runId: "01J0RUNAAAAAAAAAAAAAAAAAAA",
    originRegion: "us-east-1",
    testPlanBlobId: "blob-123",
    application: "checkout-svc",
    initiatedBy: "ci",
    state: "ABORTED",
    createdAt: "2026-05-27T12:00:00Z",
    fleetMembers: [],
  };
}

describe("AbortRunDialog", () => {
  it("confirm calls abort(runId, reason) + invokes onSuccess + onClose", async () => {
    abortMock.mockResolvedValue(abortedRun());
    const onClose = vi.fn();
    const onSuccess = vi.fn();

    render(
      <AbortRunDialog
        runId="run-1"
        activeWorkerCount={3}
        onClose={onClose}
        onSuccess={onSuccess}
      />,
    );

    expect(screen.getByRole("heading", { name: /Abort run\?/ })).toBeInTheDocument();
    expect(screen.getByText(/3 active workers/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/Reason/i), {
      target: { value: "  bad test plan  " },
    });
    fireEvent.click(screen.getByRole("button", { name: /^Abort run$/i }));
    await waitFor(() => expect(abortMock).toHaveBeenCalled());

    const [calledRunId, reason] = abortMock.mock.calls[0];
    expect(calledRunId).toBe("run-1");
    // The component passes the raw field value; the api client trims it.
    expect(reason).toBe("  bad test plan  ");
    expect(onSuccess).toHaveBeenCalledWith(
      expect.objectContaining({ runId: abortedRun().runId, state: "ABORTED" }),
    );
    expect(onClose).toHaveBeenCalled();
  });

  it("submits with empty reason when the field is left blank", async () => {
    abortMock.mockResolvedValue(abortedRun());
    render(
      <AbortRunDialog
        runId="run-1"
        activeWorkerCount={1}
        onClose={vi.fn()}
        onSuccess={vi.fn()}
      />,
    );
    // Singular copy when exactly one active worker.
    expect(screen.getByText(/1 active worker$/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /^Abort run$/i }));
    await waitFor(() => expect(abortMock).toHaveBeenCalled());
    expect(abortMock.mock.calls[0][1]).toBe("");
  });

  it("surfaces RUN_NOT_ABORTABLE and does NOT close", async () => {
    // Lazily reject on call (not an eagerly-created rejected promise at setup),
    // so there's no unhandled-rejection window before the component awaits it.
    abortMock.mockImplementationOnce(async () => {
      throw new GlobalOrchestratorError(409, "RUN_NOT_ABORTABLE", "run is terminal; nothing to abort");
    });
    const onClose = vi.fn();
    const onSuccess = vi.fn();

    render(
      <AbortRunDialog
        runId="run-1"
        activeWorkerCount={2}
        onClose={onClose}
        onSuccess={onSuccess}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /^Abort run$/i }));
    await waitFor(() => expect(abortMock).toHaveBeenCalled());
    expect(await screen.findByText(/RUN_NOT_ABORTABLE/)).toBeInTheDocument();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("Cancel calls onClose and does not call abort", () => {
    const onClose = vi.fn();
    render(
      <AbortRunDialog
        runId="run-1"
        activeWorkerCount={2}
        onClose={onClose}
        onSuccess={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /^Cancel$/i }));
    expect(onClose).toHaveBeenCalled();
    expect(abortMock).not.toHaveBeenCalled();
  });
});
