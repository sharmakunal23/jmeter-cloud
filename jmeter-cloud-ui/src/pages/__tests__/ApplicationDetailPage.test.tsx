import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { ApplicationDetailPage } from "../ApplicationDetailPage";
import type { Run } from "../../api/runs";

vi.mock("../../api/runs", async () => {
  const actual = await vi.importActual<typeof import("../../api/runs")>("../../api/runs");
  return {
    ...actual,
    runsApi: {
      ...actual.runsApi,
      listPage: vi.fn(),
      delete: vi.fn(),
    },
  };
});

import { runsApi } from "../../api/runs";

const mocks = runsApi as unknown as {
  listPage: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
};

beforeEach(() => {
  mocks.listPage.mockReset();
  mocks.delete.mockReset();
});

function fixtureRun(runId: string, overrides: Partial<Run> = {}): Run {
  return {
    runId,
    originRegion: "local-east-1",
    testPlanBlobId: "TPB",
    application: "checkout-svc",
    initiatedBy: "ui",
    state: "RUNNING",
    createdAt: "2026-05-11T12:00:00Z",
    fleetMembers: [
      { runId, workerId: "w1", region: "local-east-1", state: "RUNNING", createdAt: "2026-05-11T12:00:00Z" },
    ],
    ...overrides,
  } as Run;
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="applications/:appName" element={<ApplicationDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("ApplicationDetailPage", () => {
  it("calls runsApi.listPage with the appName from the URL + offset=0 + limit=25", async () => {
    mocks.listPage.mockResolvedValue({ runs: [], total: 0, offset: 0, limit: 25 });
    renderAt("/applications/checkout-svc");
    await waitFor(() => expect(mocks.listPage).toHaveBeenCalled());
    expect(mocks.listPage.mock.calls[0][0]).toEqual(
      expect.objectContaining({ application: "checkout-svc", offset: 0, limit: 25 }),
    );
  });

  it("header shows a Templates link to the app's template page", async () => {
    mocks.listPage.mockResolvedValue({ runs: [], total: 0, offset: 0, limit: 25 });
    renderAt("/applications/checkout-svc");
    const link = await screen.findByRole("link", { name: /Templates/i });
    expect(link.getAttribute("href")).toBe("/templates/checkout-svc");
  });

  it("shows empty-state when no runs", async () => {
    mocks.listPage.mockResolvedValue({ runs: [], total: 0, offset: 0, limit: 25 });
    renderAt("/applications/checkout-svc");
    await waitFor(() => expect(screen.getByText(/No runs for/i)).toBeInTheDocument());
  });

  it("renders a row per run; each row links to the per-app run detail URL", async () => {
    const runs = [fixtureRun("01J0RUN001"), fixtureRun("01J0RUN002")];
    mocks.listPage.mockResolvedValue({ runs, total: 2, offset: 0, limit: 25 });
    renderAt("/applications/checkout-svc");
    await waitFor(() => expect(screen.getByText("01J0RUN001")).toBeInTheDocument());
    const link = screen.getByRole("link", { name: "01J0RUN001" }) as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("/applications/checkout-svc/runs/01J0RUN001");
  });

  it("?page=2 in the URL drives offset=25 in the API call", async () => {
    mocks.listPage.mockResolvedValue({ runs: [], total: 100, offset: 25, limit: 25 });
    renderAt("/applications/checkout-svc?page=2");
    await waitFor(() => expect(mocks.listPage).toHaveBeenCalled());
    expect(mocks.listPage.mock.calls[0][0]).toEqual(
      expect.objectContaining({ application: "checkout-svc", offset: 25, limit: 25 }),
    );
  });

  it("clicking Next on the paginator navigates to ?page=2", async () => {
    const runs = Array.from({ length: 25 }, (_, i) => fixtureRun(`R${i.toString().padStart(3, "0")}`));
    mocks.listPage.mockResolvedValue({ runs, total: 100, offset: 0, limit: 25 });
    renderAt("/applications/checkout-svc");
    await waitFor(() => expect(screen.getByRole("button", { name: /next page/i })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /next page/i }));
    // Re-fetch fired with new offset. The page issues TWO calls per
    // fetch cycle (paginated + activeOnly); look for the paginated
    // one carrying offset=25 specifically.
    await waitFor(() => {
      const offsetCalls = mocks.listPage.mock.calls
        .map((c) => c[0])
        .filter((arg) => arg && (arg as { offset?: number }).offset === 25);
      expect(offsetCalls.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("Total runs chip reflects total from the API response", async () => {
    mocks.listPage.mockResolvedValue({ runs: [fixtureRun("X")], total: 73, offset: 0, limit: 25 });
    renderAt("/applications/checkout-svc");
    await waitFor(() => {
      const chips = document.querySelectorAll('.appDetailChip');
      expect(chips[0]).toHaveTextContent("Total runs");
      expect(chips[0]).toHaveTextContent("73");
    });
  });

  it("Active chip reflects the count from the activeOnly fetch", async () => {
    // First call (paginated) → 1 run, total 1; second call (activeOnly) → 4 runs.
    mocks.listPage
      .mockResolvedValueOnce({ runs: [fixtureRun("X")], total: 1, offset: 0, limit: 25 })
      .mockResolvedValueOnce({
        runs: Array.from({ length: 4 }, (_, i) => fixtureRun(`A${i}`)),
        total: 4, offset: 0, limit: 200,
      });
    renderAt("/applications/checkout-svc");
    await waitFor(() => {
      const chips = document.querySelectorAll('.appDetailChip');
      expect(chips[1]).toHaveTextContent("Active");
      expect(chips[1]).toHaveTextContent("4");
    });
  });

  it("per-row Delete is disabled for active runs, enabled for terminal runs", async () => {
    mocks.listPage.mockResolvedValue({
      runs: [
        fixtureRun("ACT", { state: "RUNNING" }),
        fixtureRun("DONE", { state: "COMPLETED" }),
      ],
      total: 2, offset: 0, limit: 25,
    });
    renderAt("/applications/checkout-svc");
    await waitFor(() => expect(screen.getByText("DONE")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: /delete run ACT/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /delete run DONE/i })).toBeEnabled();
  });

  it("clicking a row's Delete opens the confirm dialog for that run", async () => {
    mocks.listPage.mockResolvedValue({
      runs: [fixtureRun("DONE", { state: "COMPLETED" })], total: 1, offset: 0, limit: 25,
    });
    renderAt("/applications/checkout-svc");
    await waitFor(() => expect(screen.getByText("DONE")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /delete run DONE/i }));
    expect(screen.getByRole("heading", { name: /Delete 1 run\?/ })).toBeInTheDocument();
  });

  it("selecting runs reveals 'Delete selected' which opens a bulk confirm dialog", async () => {
    mocks.listPage.mockResolvedValue({
      runs: [
        fixtureRun("R1", { state: "COMPLETED" }),
        fixtureRun("R2", { state: "FAILED" }),
      ],
      total: 2, offset: 0, limit: 25,
    });
    renderAt("/applications/checkout-svc");
    await waitFor(() => expect(screen.getByText("R1")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: /Delete selected/i })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("checkbox", { name: /select R1/i }));
    fireEvent.click(screen.getByRole("checkbox", { name: /select R2/i }));
    fireEvent.click(screen.getByRole("button", { name: /Delete selected \(2\)/i }));
    expect(screen.getByRole("heading", { name: /Delete 2 runs\?/ })).toBeInTheDocument();
  });
});
