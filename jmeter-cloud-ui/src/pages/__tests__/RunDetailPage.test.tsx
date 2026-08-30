import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../../api/runs", async () => {
  const actual = await vi.importActual<typeof import("../../api/runs")>("../../api/runs");
  return { ...actual, runsApi: { ...actual.runsApi, get: vi.fn(), status: vi.fn() } };
});
// The Metrics tab pulls charts + metrics hooks; a stub keeps this test about the
// page. It records the dashboards the page hands the tab for its Grafana link.
vi.mock("../../components/MetricsTabPanel", () => ({
  MetricsTabPanel: (props: { dashboards?: unknown; run?: unknown }) => (
    <div data-testid="metricsPanel" data-dashboards={JSON.stringify(props.dashboards ?? null)} data-run={JSON.stringify(props.run ?? null)}>metrics</div>
  ),
}));
vi.mock("../../components/LogTailPanel", () => ({
  LogTailPanel: (props: { workerId: string; streamSource: string }) => (
    <div data-testid="logTailMock" data-streamsource={props.streamSource} data-workerid={props.workerId}>tail</div>
  ),
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
    expect(screen.queryByTestId("metricsPanel")).not.toBeInTheDocument();
  });

  it("once RUNNING the Metrics tab renders as before", async () => {
    const member = { runId: "01J000RUN", workerId: "jmeter-poc-na-east-worker-1", region: "na-east",
      state: "RUNNING", stateReason: null, fanoutStatusCode: 202, podBaseUrl: "http://w:8080",
      createdAt: "2026-08-28T18:00:00Z", startedAt: null, completedAt: null, properties: {}, runsServed: 1 };
    api.get.mockResolvedValue(run("RUNNING", null, [member]));
    api.status.mockResolvedValue({ runId: "01J000RUN", state: "RUNNING", stateReason: null, members: [member] });

    renderPage();

    await waitFor(() => expect(screen.getByTestId("metricsPanel")).toBeInTheDocument());
    expect(screen.queryByTestId("provisioningPanel")).not.toBeInTheDocument();
  });
});

describe("RunDetailPage — Open in Grafana", () => {
  const app = { applicationId: "01J0APP", name: "jmeter-poc", healthEndpoints: [], createdAt: "2026-08-01T00:00:00Z",
                metricsGroupId: "cps", metricsApplication: "CPS-PCI" };
  const group = { groupId: "cps", name: "Servicing MQ", createdAt: "2026-08-01T00:00:00Z", hotDays: 7,
                  grafanaLiveUrl: "https://grafana.example.com/d/cpsProductMetrics/servicing-mq?orgId=1" };

  beforeEach(() => {
    api.get.mockReset();
    api.status.mockReset();
    appsApi.list.mockReset();
    groupsApi.get.mockReset();
  });

  it("hands the Metrics tab the group's dashboards, the app's metrics name and the run's timestamps", async () => {
    api.get.mockResolvedValue({ ...run("COMPLETED", null, []), startedAt: "2026-08-30T11:00:00Z", completedAt: "2026-08-30T11:30:00Z" });
    api.status.mockResolvedValue({ runId: "01J000RUN", state: "COMPLETED", stateReason: null, members: [] });
    appsApi.list.mockResolvedValue([app]);
    groupsApi.get.mockResolvedValue(group);
    renderPage();
    const panel = await screen.findByTestId("metricsPanel");
    await waitFor(() => expect(JSON.parse(panel.getAttribute("data-dashboards")!)).toEqual({
      liveUrl: group.grafanaLiveUrl, hotDays: 7, metricsApplication: "CPS-PCI",
    }));
    expect(JSON.parse(panel.getAttribute("data-run")!)).toEqual({ startedAt: "2026-08-30T11:00:00Z", completedAt: "2026-08-30T11:30:00Z" });
    expect(groupsApi.get).toHaveBeenCalledWith("cps", expect.anything());
    // The page itself no longer renders the link — it lives on the Metrics tab's toolbar.
    expect(screen.queryByRole("link", { name: /open in grafana/i })).toBeNull();
  });

  it("the dashboards are the group's only — a stale per-app URL is ignored; a group without a URL leaves them empty", async () => {
    api.get.mockResolvedValue(run("RUNNING", null, []));
    api.status.mockResolvedValue({ runId: "01J000RUN", state: "RUNNING", stateReason: null, members: [] });
    appsApi.list.mockResolvedValue([{ ...app, grafanaLiveUrl: "https://grafana.example.com/d/own?orgId=1" }]);
    groupsApi.get.mockResolvedValue(group);
    const { unmount } = renderPage();
    const panel = await screen.findByTestId("metricsPanel");
    await waitFor(() => expect(JSON.parse(panel.getAttribute("data-dashboards")!).liveUrl).toBe(group.grafanaLiveUrl));
    unmount();

    appsApi.list.mockResolvedValue([{ ...app, metricsApplication: null }]);
    groupsApi.get.mockResolvedValue({ ...group, grafanaLiveUrl: null });
    renderPage();
    const again = await screen.findByTestId("metricsPanel");
    await waitFor(() => expect(appsApi.list).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(JSON.parse(again.getAttribute("data-dashboards")!)).toEqual({ liveUrl: null, hotDays: 7, metricsApplication: null }));
  });
});

describe("RunDetailPage — one tab row: Metrics first, Console + Logs last and live-only", () => {
  const member = { runId: "01J000RUN", workerId: "jmeter-poc-na-east-worker-1", region: "na-east",
    state: "RUNNING", stateReason: null, fanoutStatusCode: 202, podBaseUrl: "http://w:8080",
    createdAt: "2026-08-28T18:00:00Z", startedAt: null, completedAt: null, properties: {}, runsServed: 1 };

  beforeEach(() => {
    api.get.mockReset();
    api.status.mockReset();
    appsApi.list.mockReset().mockResolvedValue([]);
    groupsApi.get.mockReset();
    window.localStorage.clear();
  });

  function live() {
    api.get.mockResolvedValue(run("RUNNING", null, [member]));
    api.status.mockResolvedValue({ runId: "01J000RUN", state: "RUNNING", stateReason: null, members: [member] });
  }

  it("a live run shows Metrics · Worker Fleet · Metadata · Events · Console · Logs, Metrics selected, with the ARIA tab contract", async () => {
    live();
    renderPage();
    await screen.findByTestId("metricsPanel");
    const tabs = screen.getAllByRole("tab").map((t) => t.textContent?.replace(/\d+.*$/, "").trim());
    expect(tabs).toEqual(["Metrics", "Worker Fleet", "Metadata", "Events", "Console", "Logs"]);
    const metrics = screen.getByRole("tab", { name: /^Metrics$/ });
    expect(metrics).toHaveAttribute("aria-selected", "true");
    expect(metrics).toHaveAttribute("tabindex", "0");
    expect(metrics.id).toBe("runDetailTab-metrics");
    expect(metrics.getAttribute("aria-controls")).toBe("runDetailPanel-metrics");
    expect(screen.getByRole("tab", { name: "Console" })).toHaveAttribute("tabindex", "-1");
    expect(screen.getByRole("tabpanel").getAttribute("aria-labelledby")).toBe("runDetailTab-metrics");
  });

  it("Console and Logs mount one worker's stream each and unmount the Metrics tab; the choice is remembered", async () => {
    live();
    const first = renderPage();
    await screen.findByTestId("metricsPanel");
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    expect(screen.queryByTestId("metricsPanel")).toBeNull();
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-streamsource", "console");
    fireEvent.click(screen.getByRole("tab", { name: "Logs" }));
    expect(screen.getByTestId("logTailMock")).toHaveAttribute("data-streamsource", "jmeter");
    expect(window.localStorage.getItem("jmeterCloud.runDetailTab")).toBe("logs");
    first.unmount();

    renderPage();
    await screen.findByTestId("logTailMock");
    expect(screen.getByRole("tab", { name: "Logs" })).toHaveAttribute("aria-selected", "true");
  });

  it("keyboard: ArrowRight/ArrowLeft cycle, Home/End jump", async () => {
    live();
    renderPage();
    await screen.findByTestId("metricsPanel");
    const metrics = screen.getByRole("tab", { name: /^Metrics$/ });
    fireEvent.keyDown(metrics, { key: "End" });
    expect(screen.getByRole("tab", { name: "Logs" })).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(screen.getByRole("tab", { name: "Logs" }), { key: "ArrowRight" });
    expect(metrics).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(metrics, { key: "ArrowLeft" });
    expect(screen.getByRole("tab", { name: "Logs" })).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(screen.getByRole("tab", { name: "Logs" }), { key: "Home" });
    expect(metrics).toHaveAttribute("aria-selected", "true");
  });

  it("a finished run has no Console or Logs tab, and a stored Console choice lands on Metrics", async () => {
    window.localStorage.setItem("jmeterCloud.runDetailTab", "console");
    api.get.mockResolvedValue({ ...run("COMPLETED", null, [{ ...member, state: "COMPLETED" }]), completedAt: "2026-08-28T18:10:00Z" });
    api.status.mockResolvedValue({ runId: "01J000RUN", state: "COMPLETED", stateReason: null, members: [{ ...member, state: "COMPLETED" }] });
    renderPage();
    await screen.findByTestId("metricsPanel");
    expect(screen.queryByRole("tab", { name: "Console" })).toBeNull();
    expect(screen.queryByRole("tab", { name: "Logs" })).toBeNull();
    expect(screen.getByRole("tab", { name: /^Metrics$/ })).toHaveAttribute("aria-selected", "true");
  });
});

describe("RunDetailPage — Update properties gate (UX-DYNAMICS T5)", () => {
  beforeEach(() => {
    api.get.mockReset();
    api.status.mockReset();
  });

  const liveMember = {
    runId: "01J000RUN", workerId: "jmeter-poc-na-east-worker-1", region: "na-east",
    state: "RUNNING", stateReason: null, fanoutStatusCode: 202, podBaseUrl: "http://w:8080",
    createdAt: "2026-08-28T18:00:00Z", startedAt: null, completedAt: null, properties: {}, runsServed: 1,
  };

  it("a RUNNING run with live workers shows the button on the Worker Fleet tab", async () => {
    api.get.mockResolvedValue(run("RUNNING", null, [liveMember]));
    api.status.mockResolvedValue({ runId: "01J000RUN", state: "RUNNING", stateReason: null, members: [liveMember] });
    renderPage();
    fireEvent.click(await screen.findByRole("tab", { name: /Worker Fleet/i }));
    expect(await screen.findByRole("button", { name: /^Update properties$/ })).toBeInTheDocument();
  });

  it("a terminal run has no Update properties button", async () => {
    const doneMember = { ...liveMember, state: "COMPLETED", completedAt: "2026-08-28T18:10:00Z" };
    api.get.mockResolvedValue(run("COMPLETED", null, [doneMember]));
    api.status.mockResolvedValue({ runId: "01J000RUN", state: "COMPLETED", stateReason: null, members: [doneMember] });
    renderPage();
    fireEvent.click(await screen.findByRole("tab", { name: /Worker Fleet/i }));
    expect(screen.queryByRole("button", { name: /^Update properties$/ })).toBeNull();
  });
});
