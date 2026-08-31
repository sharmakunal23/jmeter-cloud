import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

// Selection contract: unlimited multi-select (bulk delete needs any number).
// Compare lights up only at exactly 2;
// Archive selected appears at >= 1. The page is otherwise covered by the
// integration smoke when the comparison panel is mounted (RunsComparePage tests).

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
}));
vi.mock("../../api/runs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/runs")>();
  return {
    ...actual,
    runsApi: {
      ...actual.runsApi,
      list: mocks.list,
    },
  };
});

import { RunsListPage } from "../RunsListPage";
import type { Run } from "../../api/runs";

function fixtureRun(runId: string): Run {
  return {
    runId,
    originRegion:    "us-east-1",
    testPlanBlobId:  "plan-1",
    initiatedBy:     "kunal",
    state:           "COMPLETED",
    createdAt:       "2026-05-10T20:00:00Z",
    startedAt:       "2026-05-10T20:00:01Z",
    completedAt:     "2026-05-10T20:01:00Z",
    fleetMembers:    [],
  };
}

beforeEach(() => {
  mocks.list.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

async function renderListWithRuns(runIds: string[]) {
  mocks.list.mockResolvedValue(runIds.map(fixtureRun));
  const utils = render(
    <MemoryRouter>
      <RunsListPage />
    </MemoryRouter>,
  );
  await waitFor(() => expect(screen.getByText(runIds[0]!)).toBeInTheDocument());
  return utils;
}

function checkboxFor(runId: string): HTMLInputElement {
  // The row has the runId as text + a checkbox to its left. Find the
  // row's checkbox by walking up to the table row.
  const row = screen.getByText(runId).closest("tr");
  if (!row) throw new Error(`row for ${runId} not found`);
  const cb = row.querySelector('input[type="checkbox"]');
  if (!cb) throw new Error(`checkbox for ${runId} not found`);
  return cb as HTMLInputElement;
}

describe("RunsListPage — selection: compare (exactly 2) + bulk delete (>= 1)", () => {
  it("with 0 selected, the Compare button is disabled with a 'select two runs' tooltip", async () => {
    await renderListWithRuns(["A", "B", "C"]);
    const btn = screen.getByRole("button", { name: /Compare selected/i });
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute("title", expect.stringMatching(/select two runs/i));
  });

  it("with 1 selected, the button stays disabled with a 'one more' tooltip", async () => {
    await renderListWithRuns(["A", "B", "C"]);
    fireEvent.click(checkboxFor("A"));
    const btn = screen.getByRole("button", { name: /Compare selected/i });
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute("title", expect.stringMatching(/one more/i));
  });

  it("with exactly 2 selected, the disabled button becomes a 'Compare 2 runs →' link to /runs?compare=A,B", async () => {
    await renderListWithRuns(["A", "B", "C"]);
    fireEvent.click(checkboxFor("A"));
    fireEvent.click(checkboxFor("B"));
    const link = screen.getByRole("link", { name: /Compare 2 runs/i }) as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("/applications?compare=A,B");
  });

  it("selecting a third run keeps all three selected; Compare link disappears (needs exactly 2)", async () => {
    await renderListWithRuns(["A", "B", "C"]);
    fireEvent.click(checkboxFor("A"));
    fireEvent.click(checkboxFor("B"));
    fireEvent.click(checkboxFor("C"));

    // Unlimited multi-select — all three stay checked (no auto-evict).
    expect(checkboxFor("A").checked).toBe(true);
    expect(checkboxFor("B").checked).toBe(true);
    expect(checkboxFor("C").checked).toBe(true);
    expect(screen.getByText(/3 selected/i)).toBeInTheDocument();
    // Compare is exactly-2 only → with 3 selected there's no compare link.
    expect(screen.queryByRole("link", { name: /Compare 2 runs/i })).not.toBeInTheDocument();
  });

  it("Archive selected appears at >= 1 selection and opens the confirm dialog", async () => {
    await renderListWithRuns(["A", "B", "C"]);
    // Hidden with nothing selected.
    expect(screen.queryByRole("button", { name: /Archive selected/i })).not.toBeInTheDocument();

    fireEvent.click(checkboxFor("A"));
    const del = screen.getByRole("button", { name: /Archive selected/i });
    expect(del).toBeInTheDocument();

    fireEvent.click(del);
    // The confirm dialog mounts (fixture runs are COMPLETED → archivable).
    expect(screen.getByRole("heading", { name: /Archive 1 run\?/ })).toBeInTheDocument();
  });

  it("clicking an already-selected row deselects it (toggle still works in both directions)", async () => {
    await renderListWithRuns(["A", "B", "C"]);
    fireEvent.click(checkboxFor("A"));
    fireEvent.click(checkboxFor("B"));
    expect(screen.getByText(/2 selected/i)).toBeInTheDocument();
    fireEvent.click(checkboxFor("A"));
    expect(checkboxFor("A").checked).toBe(false);
    expect(checkboxFor("B").checked).toBe(true);
    expect(screen.getByText(/1 selected/i)).toBeInTheDocument();
  });
});
