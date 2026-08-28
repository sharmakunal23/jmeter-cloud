import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../../api/runs", async () => {
  const actual = await vi.importActual<typeof import("../../api/runs")>("../../api/runs");
  return { ...actual, runsApi: { ...actual.runsApi, get: vi.fn(), status: vi.fn() } };
});
// The insights panel pulls charts + metrics hooks; a stub keeps this test about the page.
vi.mock("../../components/RunStreamsPanel", () => ({
  RunStreamsPanel: () => <div data-testid="streamsPanel">streams</div>,
}));
vi.mock("../../components/RunEventsTimeline", () => ({
  RunEventsTimeline: () => <div>events</div>,
}));

import { runsApi } from "../../api/runs";
import { RunDetailPage } from "../RunDetailPage";

const api = runsApi as unknown as { get: ReturnType<typeof vi.fn>; status: ReturnType<typeof vi.fn> };

function run(state: string, stateReason: string | null, members: unknown[]) {
  return {
    runId: "01J000RUN", originRegion: "na-east", testPlanBlobId: "b", dataFilesBlobId: null,
    application: "jmeter-poc", initiatedBy: "kunal", state, stateReason,
    createdAt: "2026-08-28T18:00:00Z", startedAt: null, completedAt: null,
    saveResults: false, fleetMembers: members,
  };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/applications/jmeter-poc/runs/01J000RUN"]}>
      <Routes>
        <Route path="applications/:appName/runs/:runId" element={<RunDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("RunDetailPage — async launch", () => {
  beforeEach(() => {
    api.get.mockReset();
    api.status.mockReset();
  });

  it("a PREPARING run shows the provisioning progress instead of empty metrics", async () => {
    const reason = "provisioning 6 worker(s) (na-east 3, na-west 3) — 2/6 ready";
    api.get.mockResolvedValue(run("PREPARING", reason, []));
    api.status.mockResolvedValue({ runId: "01J000RUN", state: "PREPARING", stateReason: reason, members: [] });

    renderPage();

    await waitFor(() => expect(screen.getByTestId("provisioningPanel")).toBeInTheDocument());
    expect(screen.getByText(reason)).toBeInTheDocument();
    expect(screen.queryByTestId("streamsPanel")).not.toBeInTheDocument();
  });

  it("once RUNNING the insights panel renders as before", async () => {
    const member = { runId: "01J000RUN", workerId: "jmeter-poc-na-east-worker-1", region: "na-east",
      state: "RUNNING", stateReason: null, fanoutStatusCode: 202, podBaseUrl: "http://w:8080",
      createdAt: "2026-08-28T18:00:00Z", startedAt: null, completedAt: null, properties: {}, runsServed: 1 };
    api.get.mockResolvedValue(run("RUNNING", null, [member]));
    api.status.mockResolvedValue({ runId: "01J000RUN", state: "RUNNING", stateReason: null, members: [member] });

    renderPage();

    await waitFor(() => expect(screen.getByTestId("streamsPanel")).toBeInTheDocument());
    expect(screen.queryByTestId("provisioningPanel")).not.toBeInTheDocument();
  });
});
