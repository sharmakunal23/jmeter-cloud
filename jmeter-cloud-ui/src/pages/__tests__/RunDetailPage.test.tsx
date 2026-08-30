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
// "Open in Grafana" reads the run's application + group once.
vi.mock("../../api/applications", async () => {
  const actual = await vi.importActual<typeof import("../../api/applications")>("../../api/applications");
  return { ...actual, applicationsApi: { ...actual.applicationsApi, list: vi.fn() } };
});
vi.mock("../../api/applicationGroups", async () => {
  const actual = await vi.importActual<typeof import("../../api/applicationGroups")>("../../api/applicationGroups");
  return { ...actual, applicationGroupsApi: { ...actual.applicationGroupsApi, get: vi.fn() } };
});

import { runsApi } from "../../api/runs";
import { applicationsApi } from "../../api/applications";
import { applicationGroupsApi } from "../../api/applicationGroups";
import { RunDetailPage } from "../RunDetailPage";

const appsApi = applicationsApi as unknown as { list: ReturnType<typeof vi.fn> };
const groupsApi = applicationGroupsApi as unknown as { get: ReturnType<typeof vi.fn> };

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

describe("RunDetailPage — Open in Grafana", () => {
  const app = { applicationId: "01J0APP", name: "jmeter-poc", healthEndpoints: [], createdAt: "2026-08-01T00:00:00Z",
                recyclePolicy: "REUSE", alwaysOn: false, metricsGroupId: "cps", metricsApplication: "CPS-PCI" };
  const group = { groupId: "cps", name: "Servicing MQ", createdAt: "2026-08-01T00:00:00Z", hotDays: 7,
                  grafanaLiveUrl: "https://grafana.example.com/d/cpsProductMetrics/servicing-mq?orgId=1" };

  beforeEach(() => {
    api.get.mockReset();
    api.status.mockReset();
    appsApi.list.mockReset();
    groupsApi.get.mockReset();
  });

  it("a terminal run links the group's dashboard with the run's exact range and var-application", async () => {
    api.get.mockResolvedValue({ ...run("COMPLETED", null, []), startedAt: "2026-08-30T11:00:00Z", completedAt: "2026-08-30T11:30:00Z" });
    api.status.mockResolvedValue({ runId: "01J000RUN", state: "COMPLETED", stateReason: null, members: [] });
    appsApi.list.mockResolvedValue([app]);
    groupsApi.get.mockResolvedValue(group);
    renderPage();
    const link = await screen.findByRole("link", { name: /open in grafana/i });
    const href = new URL(link.getAttribute("href")!);
    expect(href.pathname).toBe("/d/cpsProductMetrics/servicing-mq");
    expect(href.searchParams.get("from")).toBe(String(Date.parse("2026-08-30T11:00:00Z")));
    expect(href.searchParams.get("to")).toBe(String(Date.parse("2026-08-30T11:30:00Z")));
    expect(href.searchParams.get("var-application")).toBe("CPS-PCI");
    expect(href.searchParams.get("refresh")).toBeNull();
    expect(link).toHaveAttribute("target", "_blank");
    expect(groupsApi.get).toHaveBeenCalledWith("cps", expect.anything());
  });

  it("the app's own URL wins over the group's; no URL anywhere hides the button", async () => {
    api.get.mockResolvedValue(run("RUNNING", null, []));
    api.status.mockResolvedValue({ runId: "01J000RUN", state: "RUNNING", stateReason: null, members: [] });
    appsApi.list.mockResolvedValue([{ ...app, grafanaLiveUrl: "https://grafana.example.com/d/own?orgId=1" }]);
    groupsApi.get.mockResolvedValue(group);
    const { unmount } = renderPage();
    const link = await screen.findByRole("link", { name: /open in grafana/i });
    expect(link.getAttribute("href")).toMatch(/^https:\/\/grafana\.example\.com\/d\/own\?orgId=1&from=.*&to=now&refresh=15s/);
    unmount();

    appsApi.list.mockResolvedValue([{ ...app, metricsGroupId: null, metricsApplication: null }]);
    renderPage();
    await screen.findByTestId("streamsPanel");
    await waitFor(() => expect(appsApi.list).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole("link", { name: /open in grafana/i })).toBeNull();
  });
});
