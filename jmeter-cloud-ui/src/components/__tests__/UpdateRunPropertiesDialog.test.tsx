import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { axe } from "vitest-axe";

vi.mock("../../api/runs", async () => {
  const actual = await vi.importActual<typeof import("../../api/runs")>("../../api/runs");
  return { ...actual, runsApi: { ...actual.runsApi, updateProperties: vi.fn() } };
});
import { GlobalOrchestratorError, runsApi, type Run } from "../../api/runs";
import { UpdateRunPropertiesDialog } from "../UpdateRunPropertiesDialog";

const upd = runsApi.updateProperties as unknown as ReturnType<typeof vi.fn>;

function member(workerId: string, state = "RUNNING") {
  return { runId: "r1", workerId, region: "na-east", state, createdAt: "2026-08-30T00:00:00Z" };
}

function runFixture(): Run {
  return {
    runId: "r1", originRegion: "na-east", testPlanBlobId: "b", initiatedBy: "op",
    state: "RUNNING", createdAt: "2026-08-30T00:00:00Z",
    fleetMembers: [member("w1"), member("w2", "ACCEPTED"), member("w3", "COMPLETED")],
  } as unknown as Run;
}

async function fillProperty() {
  fireEvent.click(screen.getByRole("button", { name: /\+ Add property/i }));
  fireEvent.change(screen.getByLabelText("global property 1 key"), { target: { value: "rampSeconds" } });
  fireEvent.change(screen.getByLabelText("global property 1 value"), { target: { value: "120" } });
  // queueMicrotask-driven onChange — flush before asserting.
  await Promise.resolve();
  await Promise.resolve();
}

describe("UpdateRunPropertiesDialog", () => {
  beforeEach(() => upd.mockReset());

  it("lists only active members, default-all — explicit workerIds always sent", async () => {
    upd.mockResolvedValue({
      runId: "r1", requested: 2, applied: ["rampSeconds"],
      results: [{ workerId: "w1", ok: true, statusCode: 200 }, { workerId: "w2", ok: true, statusCode: 200 }],
    });
    const onSuccess = vi.fn();
    render(<UpdateRunPropertiesDialog run={runFixture()} onClose={vi.fn()} onSuccess={onSuccess} />);
    expect(screen.queryByText("w3")).toBeNull(); // terminal member not offered
    await fillProperty();
    fireEvent.click(screen.getByRole("button", { name: /Send properties/i }));
    await waitFor(() => expect(upd).toHaveBeenCalled());
    expect(upd.mock.calls[0][1]).toEqual({ workerIds: ["w1", "w2"], properties: { rampSeconds: "120" } });
    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(2));
  });

  it("deselecting one worker sends explicit workerIds", async () => {
    upd.mockResolvedValue({
      runId: "r1", requested: 1, applied: ["rampSeconds"],
      results: [{ workerId: "w1", ok: true, statusCode: 200 }],
    });
    render(<UpdateRunPropertiesDialog run={runFixture()} onClose={vi.fn()} onSuccess={vi.fn()} />);
    fireEvent.click(screen.getByRole("checkbox", { name: /w2/ }));
    await fillProperty();
    fireEvent.click(screen.getByRole("button", { name: /Send properties/i }));
    await waitFor(() => expect(upd).toHaveBeenCalled());
    expect((upd.mock.calls[0][1] as { workerIds?: string[] }).workerIds).toEqual(["w1"]);
  });

  it("a failed row renders in the per-worker results and keeps the dialog open", async () => {
    upd.mockResolvedValue({
      runId: "r1", requested: 2, applied: [],
      results: [
        { workerId: "w1", ok: true, statusCode: 200 },
        { workerId: "w2", ok: false, statusCode: 502, error: "BEANSHELL_UNREACHABLE" },
      ],
    });
    const onSuccess = vi.fn();
    const onClose = vi.fn();
    render(<UpdateRunPropertiesDialog run={runFixture()} onClose={onClose} onSuccess={onSuccess} />);
    await fillProperty();
    fireEvent.click(screen.getByRole("button", { name: /Send properties/i }));
    await waitFor(() => expect(screen.getByText(/BEANSHELL_UNREACHABLE/)).toBeInTheDocument());
    expect(screen.getByRole("list", { name: /Per-worker results/i })).toBeInTheDocument();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("409 RUN_NOT_RUNNING surfaces inline", async () => {
    upd.mockRejectedValueOnce(
      new GlobalOrchestratorError(409, "RUN_NOT_RUNNING", "the run is not RUNNING"));
    render(<UpdateRunPropertiesDialog run={runFixture()} onClose={vi.fn()} onSuccess={vi.fn()} />);
    await fillProperty();
    fireEvent.click(screen.getByRole("button", { name: /Send properties/i }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(/RUN_NOT_RUNNING/));
  });

  it("states the ${__P(name)} limitation in the InfoTip", () => {
    render(<UpdateRunPropertiesDialog run={runFixture()} onClose={vi.fn()} onSuccess={vi.fn()} />);
    expect(screen.getByText(/\$\{__P\(name\)\}/)).toBeInTheDocument();
  });

  it("Send is disabled until a worker and a property are set", async () => {
    render(<UpdateRunPropertiesDialog run={runFixture()} onClose={vi.fn()} onSuccess={vi.fn()} />);
    expect(screen.getByRole("button", { name: /Send properties/i })).toBeDisabled();
    await fillProperty();
    expect(screen.getByRole("button", { name: /Send properties/i })).toBeEnabled();
    fireEvent.click(screen.getByRole("checkbox", { name: "Select all workers" })); // deselect all
    expect(screen.getByRole("button", { name: /Send properties/i })).toBeDisabled();
  });

  it("has no axe violations", async () => {
    const { container } = render(
      <UpdateRunPropertiesDialog run={runFixture()} onClose={vi.fn()} onSuccess={vi.fn()} />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
