import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { DeleteRunsConfirmDialog } from "../DeleteRunsConfirmDialog";
import { GlobalOrchestratorError, type Run } from "../../api/runs";

vi.mock("../../api/runs", async () => {
  const actual = await vi.importActual<typeof import("../../api/runs")>("../../api/runs");
  return {
    ...actual,
    runsApi: {
      ...actual.runsApi,
      delete: vi.fn(),
    },
  };
});
import { runsApi } from "../../api/runs";

const deleteMock = runsApi.delete as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => deleteMock.mockReset());

function run(runId: string, state: Run["state"]): Run {
  return {
    runId,
    originRegion: "us-east-1",
    testPlanBlobId: "blob-123",
    application: "checkout-svc",
    initiatedBy: "ci",
    state,
    createdAt: "2026-05-27T12:00:00Z",
    fleetMembers: [],
  };
}

describe("DeleteRunsConfirmDialog", () => {
  it("archives only terminal runs (skips active) and reports the archived ids", async () => {
    deleteMock.mockResolvedValue(run("x", "COMPLETED"));
    const onDeleted = vi.fn();
    const onClose = vi.fn();

    render(
      <DeleteRunsConfirmDialog
        selected={[run("r1", "COMPLETED"), run("r2", "RUNNING"), run("r3", "FAILED")]}
        onDeleted={onDeleted}
        onClose={onClose}
      />,
    );

    expect(screen.getByRole("heading", { name: /Archive 3 runs\?/ })).toBeInTheDocument();
    // 2 terminal will archive, 1 active is skipped — shown as the two list sections.
    expect(screen.getByRole("heading", { name: /Will archive \(2\)/ })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Skipped \(1\)/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /^Archive 2$/ }));
    await waitFor(() => expect(deleteMock).toHaveBeenCalledTimes(2));

    const ids = deleteMock.mock.calls.map((c) => c[0]);
    expect(ids).toEqual(expect.arrayContaining(["r1", "r3"]));
    expect(ids).not.toContain("r2"); // active run never hit the API

    await waitFor(() =>
      expect(onDeleted).toHaveBeenCalledWith(expect.arrayContaining(["r1", "r3"])),
    );
    expect(onClose).toHaveBeenCalled();
  });

  it("disables Archive when every selected run is still active", () => {
    render(
      <DeleteRunsConfirmDialog
        selected={[run("r1", "RUNNING"), run("r2", "DRAINING")]}
        onDeleted={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByText(/All selected runs are still active/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Archive 0/ })).toBeDisabled();
  });

  it("on partial failure reports the successes, surfaces the error, and stays open", async () => {
    // r2 fails (lazy throw — no unhandled-rejection window), r1 succeeds.
    deleteMock.mockImplementation(async (runId: string) => {
      if (runId === "r2") {
        throw new GlobalOrchestratorError(409, "RUN_NOT_DELETABLE", "still active");
      }
      return run(runId, "COMPLETED");
    });
    const onDeleted = vi.fn();
    const onClose = vi.fn();

    render(
      <DeleteRunsConfirmDialog
        selected={[run("r1", "COMPLETED"), run("r2", "COMPLETED")]}
        onDeleted={onDeleted}
        onClose={onClose}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /^Archive 2$/ }));
    await waitFor(() => expect(deleteMock).toHaveBeenCalledTimes(2));

    expect(await screen.findByText(/RUN_NOT_DELETABLE/)).toBeInTheDocument();
    expect(onDeleted).toHaveBeenCalledWith(["r1"]); // success surfaced despite the failure
    expect(onClose).not.toHaveBeenCalled();         // dialog stays so the operator sees the error
  });

  it("Cancel calls onClose and does not call delete", () => {
    const onClose = vi.fn();
    render(
      <DeleteRunsConfirmDialog
        selected={[run("r1", "COMPLETED")]}
        onDeleted={vi.fn()}
        onClose={onClose}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /^Cancel$/ }));
    expect(onClose).toHaveBeenCalled();
    expect(deleteMock).not.toHaveBeenCalled();
  });
});
