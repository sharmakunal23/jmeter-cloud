import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

/**
 * The launcher's per-region ceiling comes from the application's GROUP
 * (the pool it runs on): app → metricsGroupId → group.capacity. The two
 * fleet widgets are stubbed to what they receive as `maxByRegion`.
 */
vi.mock("../../components/FleetFlowDiagram", async () => {
  const actual = await vi.importActual<typeof import("../../components/FleetFlowDiagram")>("../../components/FleetFlowDiagram");
  return {
    ...actual,
    FleetFlowDiagram: (props: { maxByRegion?: Record<string, number> }) => (
      <div data-testid="fleetDiagram" data-max={JSON.stringify(props.maxByRegion ?? null)} />
    ),
  };
});
vi.mock("../../components/FleetAllocationFormView", () => ({
  FleetAllocationFormView: (props: { maxByRegion?: Record<string, number> }) => (
    <div data-testid="fleetForm" data-max={JSON.stringify(props.maxByRegion ?? null)} />
  ),
}));
vi.mock("../../api/blobs", () => ({ blobsApi: { list: vi.fn().mockResolvedValue({ items: [], total: 0 }), upload: vi.fn() } }));
vi.mock("../../api/regions", () => ({ regionsApi: { list: vi.fn().mockResolvedValue([]) } }));
vi.mock("../../api/templates", async () => {
  const actual = await vi.importActual<typeof import("../../api/templates")>("../../api/templates");
  return { ...actual, templatesApi: { ...actual.templatesApi, load: vi.fn(), list: vi.fn().mockResolvedValue([]) } };
});
vi.mock("../../api/applications", () => ({ applicationsApi: { list: vi.fn() } }));
vi.mock("../../api/applicationGroups", () => ({ applicationGroupsApi: { get: vi.fn() } }));

import { applicationsApi } from "../../api/applications";
import { applicationGroupsApi } from "../../api/applicationGroups";
import { NewRunPage } from "../NewRunPage";

const appsList = applicationsApi.list as unknown as ReturnType<typeof vi.fn>;
const groupGet = applicationGroupsApi.get as unknown as ReturnType<typeof vi.fn>;

function renderAt(appName: string) {
  return render(
    <MemoryRouter initialEntries={[`/applications/${appName}/runs/new`]}>
      <Routes>
        <Route path="applications/:appName/runs/new" element={<NewRunPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  appsList.mockReset();
  groupGet.mockReset();
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
    ok: true, status: 200,
    json: async () => ({ provisioningMode: "DYNAMIC", dynamicScalingEnabled: true, podRecyclingEnabled: true, regions: [], regionLabel: "region" }),
  }));
});

describe("NewRunPage — the per-region ceiling is the group's", () => {
  it("resolves the URL app → its group and hands the group's capacity grid to the fleet widgets", async () => {
    appsList.mockResolvedValue([
      { applicationId: "01APP", name: "checkout-svc", healthEndpoints: [], metricsGroupId: "cps", createdAt: "2026-05-15T12:00:00Z" },
    ]);
    groupGet.mockResolvedValue({
      groupId: "cps", name: "Servicing MQ", createdAt: "2026-05-15T12:00:00Z",
      capacity: [{ region: "us-east", maxAvailable: 8 }, { region: "us-west", maxAvailable: 2 }],
    });
    renderAt("checkout-svc");
    await waitFor(() => expect(groupGet).toHaveBeenCalledWith("cps", expect.anything()));
    await waitFor(() =>
      expect(screen.getByTestId("fleetForm").getAttribute("data-max")).toBe(JSON.stringify({ "us-east": 8, "us-west": 2 })));
  });

  it("an unknown app or a failed group read leaves the ceiling at the legacy idle-pod bound (empty map)", async () => {
    appsList.mockResolvedValue([]);
    renderAt("ghost-svc");
    await waitFor(() => expect(appsList).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByTestId("fleetForm").getAttribute("data-max")).toBe("{}"));
    expect(groupGet).not.toHaveBeenCalled();
  });
});
