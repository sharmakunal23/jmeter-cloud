import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

// ── Mock the runs API so the per-run header poll resolves with known
//    fixtures without hitting the network. ─────────────────────────────
const mocks = vi.hoisted(() => ({
  get: vi.fn(),
}));
vi.mock("../../api/runs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/runs")>();
  return {
    ...actual,
    runsApi: {
      ...actual.runsApi,
      get: mocks.get,
    },
  };
});

// ── Mock TwoRunMetricsPanel — the page test asserts on what the page
//    passes to it (runIds, runStates), not on the panel's chart math
//    (covered in TwoRunMetricsPanel.test.tsx). ───────────────────────
const panelCalls = vi.hoisted(() => ({
  instances: [] as Array<{
    runIdA: string;
    runIdB: string;
    runStateA: string | null;
    runStateB: string | null;
  }>,
}));
vi.mock("../../components/TwoRunMetricsPanel", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../components/TwoRunMetricsPanel")>();
  return {
    ...actual,
    TwoRunMetricsPanel: (props: {
      runIdA: string;
      runIdB: string;
      runStateA?: string | null;
      runStateB?: string | null;
    }) => {
      panelCalls.instances.push({
        runIdA: props.runIdA,
        runIdB: props.runIdB,
        runStateA: props.runStateA ?? null,
        runStateB: props.runStateB ?? null,
      });
      return <div data-testid="twoRunPanelMock">panel: {props.runIdA} / {props.runIdB}</div>;
    },
  };
});

import { RunsComparePage } from "../RunsComparePage";
import type { Run } from "../../api/runs";

function fixtureRun(runId: string, state: Run["state"] = "COMPLETED"): Run {
  return {
    runId,
    originRegion:    "us-east-1",
    testPlanBlobId:  "plan-1",
    initiatedBy:     "kunal",
    state,
    createdAt:       "2026-05-10T20:00:00Z",
    startedAt:       "2026-05-10T20:00:01Z",
    completedAt:     state === "COMPLETED" ? "2026-05-10T20:01:00Z" : null,
    fleetMembers:    [],
  };
}

beforeEach(() => {
  mocks.get.mockReset();
  panelCalls.instances = [];
});

afterEach(() => {
  vi.clearAllMocks();
});

function renderPage(runIds: string[]) {
  return render(
    <MemoryRouter>
      <RunsComparePage runIds={runIds} />
    </MemoryRouter>,
  );
}

describe("RunsComparePage — strict 2-run validation", () => {
  it("renders an error (no panel) when only one run id is provided", () => {
    renderPage(["A"]);
    expect(screen.queryByTestId("twoRunPanelMock")).toBeNull();
    expect(screen.getByText(/exactly two distinct run ids/i)).toBeInTheDocument();
  });

  it("renders an error when three+ ids are provided", () => {
    renderPage(["A", "B", "C"]);
    expect(screen.queryByTestId("twoRunPanelMock")).toBeNull();
    expect(screen.getByText(/exactly two distinct run ids/i)).toBeInTheDocument();
  });

  it("renders an error when ids dedupe to one (e.g. ?compare=A,A)", () => {
    renderPage(["A", "A"]);
    expect(screen.queryByTestId("twoRunPanelMock")).toBeNull();
    expect(screen.getByText(/after deduplicating/i)).toBeInTheDocument();
  });

  it("does NOT call the runs API in the error path (no wasted fetch)", () => {
    renderPage(["A"]);
    expect(mocks.get).not.toHaveBeenCalled();
  });
});

describe("RunsComparePage — happy 2-run path", () => {
  it("renders TwoRunMetricsPanel with the two ids + propagates fetched run states", async () => {
    mocks.get.mockImplementation((id: string) =>
      Promise.resolve(fixtureRun(id, id === "A" ? "COMPLETED" : "RUNNING")),
    );
    renderPage(["A", "B"]);

    expect(screen.getByTestId("twoRunPanelMock")).toBeInTheDocument();
    // Initial call passes nulls (state hasn't loaded yet).
    expect(panelCalls.instances[0]).toEqual({
      runIdA: "A", runIdB: "B", runStateA: null, runStateB: null,
    });

    // After both fetches resolve, the panel re-renders with the
    // resolved states.
    await waitFor(() => {
      const last = panelCalls.instances[panelCalls.instances.length - 1]!;
      expect(last.runStateA).toBe("COMPLETED");
      expect(last.runStateB).toBe("RUNNING");
    });
  });

  it("renders a column per run with a colored swatch matching the chart palette", async () => {
    mocks.get.mockResolvedValue(fixtureRun("A"));
    const { container } = renderPage(["A", "B"]);
    const swatches = container.querySelectorAll(".compareColumn__swatch");
    expect(swatches).toHaveLength(2);
    // Inline styles use the same palette as TwoRunMetricsPanel.
    expect((swatches[0] as HTMLElement).style.background).toBeTruthy();
    expect((swatches[1] as HTMLElement).style.background).toBeTruthy();
    expect((swatches[0] as HTMLElement).style.background)
      .not.toBe((swatches[1] as HTMLElement).style.background);
  });

  it("each column shows its runId as a link to the run-detail page", async () => {
    mocks.get.mockResolvedValue(fixtureRun("A"));
    renderPage(["A", "B"]);
    const linkA = screen.getByRole("link", { name: "A" }) as HTMLAnchorElement;
    const linkB = screen.getByRole("link", { name: "B" }) as HTMLAnchorElement;
    // UI-D1 IA cutover: run-detail moved under /applications/:appName/.
    // appName is unknown client-side until D3 enriches the run record,
    // so the placeholder `_` is used.
    expect(linkA.getAttribute("href")).toBe("/applications/_/runs/A");
    expect(linkB.getAttribute("href")).toBe("/applications/_/runs/B");
  });

  it("'Back to runs' link is present", () => {
    mocks.get.mockResolvedValue(fixtureRun("A"));
    renderPage(["A", "B"]);
    expect(screen.getByRole("link", { name: /Back to runs/i })).toHaveAttribute("href", "/applications");
  });

  it("does NOT re-fetch in a loop — exactly one GET per run on load (request-storm regression)", async () => {
    // Regression for the render→fetch→setState→render loop: `runIds` is a
    // fresh array each render, so an unmemoised `distinctIds` gave `fetchAll`
    // a new identity every render, its effect re-ran, and each resolved GET
    // kicked off two more. Both runs terminal here → no polling either.
    mocks.get.mockImplementation((id: string) => Promise.resolve(fixtureRun(id, "COMPLETED")));
    renderPage(["A", "B"]);

    await waitFor(() => {
      const last = panelCalls.instances[panelCalls.instances.length - 1]!;
      expect(last.runStateA).toBe("COMPLETED");
      expect(last.runStateB).toBe("COMPLETED");
    });
    // Let any stray re-render settle, then assert exactly one fetch per run.
    await Promise.resolve();
    expect(mocks.get).toHaveBeenCalledTimes(2);
    expect(mocks.get).toHaveBeenCalledWith("A", expect.anything());
    expect(mocks.get).toHaveBeenCalledWith("B", expect.anything());
  });

  it("keeps polling while a run is active, but stops once both are terminal", async () => {
    vi.useFakeTimers();
    try {
      // Both terminal → poll pauses after the initial load. What matters is
      // that the count stops GROWING; how many reads the initial render
      // settles on is an implementation detail, and asserting it exactly made
      // this test flake under load.
      mocks.get.mockResolvedValue(fixtureRun("A", "COMPLETED"));
      const { unmount } = renderPage(["A", "B"]);
      await vi.advanceTimersByTimeAsync(0);          // flush initial fetch
      expect(mocks.get.mock.calls.length).toBeGreaterThanOrEqual(2);
      expect(mocks.get).toHaveBeenCalledWith("A", expect.anything());
      expect(mocks.get).toHaveBeenCalledWith("B", expect.anything());
      const afterLoad = mocks.get.mock.calls.length;
      await vi.advanceTimersByTimeAsync(5_000 * 3);  // 3 would-be poll cycles
      expect(mocks.get.mock.calls.length).toBe(afterLoad);   // paused
      unmount();

      // An active run → polling continues.
      mocks.get.mockReset();
      mocks.get.mockResolvedValue(fixtureRun("C", "RUNNING"));
      renderPage(["C", "D"]);
      await vi.advanceTimersByTimeAsync(0);
      const activeAfterLoad = mocks.get.mock.calls.length;
      expect(activeAfterLoad).toBeGreaterThanOrEqual(2);
      await vi.advanceTimersByTimeAsync(5_000);      // one poll cycle
      expect(mocks.get.mock.calls.length).toBeGreaterThan(activeAfterLoad);
    } finally {
      vi.useRealTimers();
    }
  });

  it("when one column's GET fails, the other still renders + the panel is mounted", async () => {
    mocks.get.mockImplementation((id: string) => {
      if (id === "B") return Promise.reject(new Error("not found"));
      return Promise.resolve(fixtureRun("A", "COMPLETED"));
    });
    renderPage(["A", "B"]);

    // Panel still mounts (the timeseries fetch is independent of the
    // per-column header fetch).
    expect(screen.getByTestId("twoRunPanelMock")).toBeInTheDocument();

    await waitFor(() => {
      // A's badge renders.
      expect(screen.getByText("COMPLETED")).toBeInTheDocument();
      // B's error surfaces in its column.
      expect(screen.getByText(/not found/i)).toBeInTheDocument();
    });
  });
});
