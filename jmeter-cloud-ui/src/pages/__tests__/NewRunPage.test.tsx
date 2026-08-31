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
vi.mock("../../api/blobs", () => ({ blobsApi: { list: vi.fn().mockResolvedValue({ items: [], total: 0 }), upload: vi.fn(), metadata: vi.fn() } }));
vi.mock("../../api/regions", () => ({ regionsApi: { list: vi.fn().mockResolvedValue([]) } }));
vi.mock("../../api/templates", async () => {
  const actual = await vi.importActual<typeof import("../../api/templates")>("../../api/templates");
  return { ...actual, templatesApi: { ...actual.templatesApi, load: vi.fn(), list: vi.fn().mockResolvedValue([]), save: vi.fn() } };
});
vi.mock("../../api/plugins", async () => {
  const actual = await vi.importActual<typeof import("../../api/plugins")>("../../api/plugins");
  return { ...actual, pluginsApi: { list: vi.fn().mockResolvedValue([]), create: vi.fn(), delete: vi.fn() } };
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
    // `fresh` is the contract, not an incidental argument: this ceiling gates
    // an action, so it must never come from the navigation cache.
    await waitFor(() => expect(groupGet)
      .toHaveBeenCalledWith("cps", expect.anything(), { fresh: true }));
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

// ── UX-DYNAMICS T2 — template hydration fidelity ──────────────────────
import { fireEvent, within } from "@testing-library/react";
import { blobsApi } from "../../api/blobs";
import { pluginsApi } from "../../api/plugins";
import { templatesApi } from "../../api/templates";

const blobsList = blobsApi.list as unknown as ReturnType<typeof vi.fn>;
const blobsMetadata = blobsApi.metadata as unknown as ReturnType<typeof vi.fn>;
const pluginsList = pluginsApi.list as unknown as ReturnType<typeof vi.fn>;
const tplLoad = templatesApi.load as unknown as ReturnType<typeof vi.fn>;
const tplSave = templatesApi.save as unknown as ReturnType<typeof vi.fn>;

function renderWithTemplate(appName: string, templateId: string) {
  return render(
    <MemoryRouter initialEntries={[`/applications/${appName}/runs/new?template=${templateId}`]}>
      <Routes>
        <Route path="applications/:appName/runs/new" element={<NewRunPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

function mockApp() {
  appsList.mockResolvedValue([
    { applicationId: "01APP", name: "checkout-svc", healthEndpoints: [], metricsGroupId: "cps", createdAt: "2026-05-15T12:00:00Z" },
  ]);
  groupGet.mockResolvedValue({
    groupId: "cps", name: "Servicing MQ", createdAt: "2026-05-15T12:00:00Z",
    capacity: [{ region: "us-east", maxAvailable: 8 }],
  });
}

const PLAN_META = { blobId: "plan-1", name: "plan.jmx", sizeBytes: 100, sha256: "x", uploadedAt: "2026-08-30T00:00:00Z" };
const DATA_META = { blobId: "data-1", name: "data.zip", sizeBytes: 200, sha256: "y", uploadedAt: "2026-08-30T00:00:00Z" };

function tplBody(overrides: Record<string, unknown> = {}) {
  return {
    v: 1, application: "checkout-svc", testPlanBlobId: "plan-1", dataFilesBlobId: "data-1",
    fleetAllocation: [{ region: "us-east", count: 2, perNodeProperties: [{ threads: "10" }, {}] }],
    globalProperties: { USER_OFFSET: "100" }, saveResults: true, ...overrides,
  };
}

describe("NewRunPage — template hydration (UX-DYNAMICS T2)", () => {
  beforeEach(() => {
    blobsList.mockReset();
    blobsMetadata.mockReset();
    tplLoad.mockReset();
    tplSave.mockReset().mockResolvedValue("tpl-blob-9");
    pluginsList.mockReset().mockResolvedValue([]);
  });

  it("restores plan, data files, save-results and global properties from the template", async () => {
    mockApp();
    blobsList.mockImplementation((opts: { type: string }) =>
      Promise.resolve({ items: opts.type === "testPlan" ? [PLAN_META] : [DATA_META], total: 1 }));
    tplLoad.mockResolvedValue(tplBody());
    renderWithTemplate("checkout-svc", "tpl1");
    await waitFor(() => expect(screen.getByLabelText(/Test plan/i)).toHaveValue("plan-1"));
    expect(screen.getByLabelText(/^Data files$/i)).toHaveValue("data-1");
    expect(screen.getByLabelText(/Save results/i)).toBeChecked();
    await waitFor(() => expect(screen.getByDisplayValue("USER_OFFSET")).toBeInTheDocument());
    expect(blobsMetadata).not.toHaveBeenCalled();
  });

  it("a plan the list doesn't contain but metadata finds renders '(from template)' and stays launchable", async () => {
    mockApp();
    blobsList.mockResolvedValue({ items: [], total: 0 });
    blobsMetadata.mockResolvedValue(PLAN_META);
    tplLoad.mockResolvedValue(tplBody({ dataFilesBlobId: undefined }));
    renderWithTemplate("checkout-svc", "tpl1");
    await waitFor(() => expect(screen.getByText("plan.jmx (from template)")).toBeInTheDocument());
    expect(screen.getByLabelText(/Test plan/i)).toHaveValue("plan-1");
    await waitFor(() => expect(screen.getByRole("button", { name: /Start run/i })).toBeEnabled());
  });

  it("a 404 plan blocks submit with a visible alert", async () => {
    mockApp();
    blobsList.mockResolvedValue({ items: [], total: 0 });
    blobsMetadata.mockRejectedValue({ httpStatus: 404 });
    tplLoad.mockResolvedValue(tplBody({ dataFilesBlobId: undefined }));
    renderWithTemplate("checkout-svc", "tpl1");
    await waitFor(() => expect(screen.getByText(/test plan no longer exists/i)).toBeInTheDocument());
    expect(screen.getByRole("button", { name: /Start run/i })).toBeDisabled();
  });

  it("a 404 data-files blob warns and clears the selection (non-blocking)", async () => {
    mockApp();
    blobsList.mockImplementation((opts: { type: string }) =>
      Promise.resolve({ items: opts.type === "testPlan" ? [PLAN_META] : [], total: 1 }));
    blobsMetadata.mockRejectedValue({ httpStatus: 404 });
    tplLoad.mockResolvedValue(tplBody());
    renderWithTemplate("checkout-svc", "tpl1");
    await waitFor(() => expect(screen.getByText(/data files no longer exist/i)).toBeInTheDocument());
    expect(screen.getByLabelText(/^Data files$/i)).toHaveValue("");
    await waitFor(() => expect(screen.getByRole("button", { name: /Start run/i })).toBeEnabled());
  });

  it("hydration failure shows the persistent banner", async () => {
    mockApp();
    blobsList.mockResolvedValue({ items: [], total: 0 });
    tplLoad.mockRejectedValue(new Error("410 gone"));
    renderWithTemplate("checkout-svc", "tpl1");
    await waitFor(() => expect(screen.getByText(/Couldn't load the template/i)).toBeInTheDocument());
  });

  it("Save template captures the launch-identical (trimmed) allocation", async () => {
    mockApp();
    blobsList.mockImplementation((opts: { type: string }) =>
      Promise.resolve({ items: opts.type === "testPlan" ? [PLAN_META] : [DATA_META], total: 1 }));
    tplLoad.mockResolvedValue(tplBody());
    renderWithTemplate("checkout-svc", "tpl1");
    await waitFor(() => expect(screen.getByLabelText(/Test plan/i)).toHaveValue("plan-1"));
    fireEvent.click(screen.getByRole("button", { name: /Save template/i }));
    const dlg = within(screen.getByRole("dialog"));
    fireEvent.change(dlg.getByLabelText(/Template name/i), { target: { value: "t2" } });
    fireEvent.click(dlg.getByRole("button", { name: /Save template/i }));
    await waitFor(() => expect(tplSave).toHaveBeenCalled());
    const body = tplSave.mock.calls[0][0] as {
      v: number; saveResults?: boolean; labelFilter?: unknown; pluginIds?: string[];
      fleetAllocation: Array<{ perNodeProperties?: unknown }>;
    };
    expect(body.fleetAllocation[0].perNodeProperties).toEqual([{ threads: "10" }]);
    // v2 semantics: explicit saveResults, no labelFilter ever written.
    expect(body.v).toBe(2);
    expect(body.saveResults).toBe(true);
    expect("labelFilter" in body).toBe(false);
  });

  it("the label-filter field is gone and a v1 labelFilter is ignored", async () => {
    mockApp();
    blobsList.mockImplementation((opts: { type: string }) =>
      Promise.resolve({ items: opts.type === "testPlan" ? [PLAN_META] : [DATA_META], total: 1 }));
    tplLoad.mockResolvedValue(tplBody({ labelFilter: "GET /api/foo, POST /api/bar" }));
    renderWithTemplate("checkout-svc", "tpl1");
    await waitFor(() => expect(screen.getByLabelText(/Test plan/i)).toHaveValue("plan-1"));
    expect(screen.queryByLabelText(/Label filter/i)).toBeNull();
    expect(screen.getByLabelText("Plugins")).toBeInTheDocument();
  });

  it("template pluginIds hydrate as chips and ride the v2 save body", async () => {
    mockApp();
    pluginsList.mockResolvedValue([
      { pluginId: "p1", name: "jpgc-casutg", version: "3.1", sizeBytes: 2048, sha256: "a", fileName: "casutg.jar", createdAt: "2026-08-30T00:00:00Z" },
    ]);
    blobsList.mockImplementation((opts: { type: string }) =>
      Promise.resolve({ items: opts.type === "testPlan" ? [PLAN_META] : [DATA_META], total: 1 }));
    tplLoad.mockResolvedValue(tplBody({ pluginIds: ["p1"] }));
    renderWithTemplate("checkout-svc", "tpl1");
    await waitFor(() => expect(screen.getByText("jpgc-casutg@3.1")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /Save template/i }));
    const dlg = within(screen.getByRole("dialog"));
    fireEvent.change(dlg.getByLabelText(/Template name/i), { target: { value: "t3" } });
    fireEvent.click(dlg.getByRole("button", { name: /Save template/i }));
    await waitFor(() => expect(tplSave).toHaveBeenCalled());
    expect((tplSave.mock.calls[0][0] as { pluginIds?: string[] }).pluginIds).toEqual(["p1"]);
  });

  it("a hydrated plugin id gone from the library warns and is excluded", async () => {
    mockApp();
    pluginsList.mockResolvedValue([]);
    blobsList.mockImplementation((opts: { type: string }) =>
      Promise.resolve({ items: opts.type === "testPlan" ? [PLAN_META] : [DATA_META], total: 1 }));
    tplLoad.mockResolvedValue(tplBody({ pluginIds: ["01GONE00000000000000000000"] }));
    renderWithTemplate("checkout-svc", "tpl1");
    await waitFor(() => expect(screen.getByText(/removed from library/)).toBeInTheDocument());
    expect(screen.getByText(/no longer in the library — excluded/)).toBeInTheDocument();
  });
});

describe("NewRunPage — update data files checkbox (UX-DYNAMICS T4)", () => {
  beforeEach(() => {
    blobsList.mockReset();
    blobsMetadata.mockReset();
    tplLoad.mockReset();
  });

  it("sends refreshDataFiles only when data files are selected and the box is checked", async () => {
    mockApp();
    blobsList.mockImplementation((opts: { type: string }) =>
      Promise.resolve({ items: opts.type === "testPlan" ? [PLAN_META] : [DATA_META], total: 1 }));
    tplLoad.mockResolvedValue(tplBody());
    renderWithTemplate("checkout-svc", "tpl1");
    await waitFor(() => expect(screen.getByLabelText(/Test plan/i)).toHaveValue("plan-1"));

    const box = screen.getByLabelText(/Update data files on workers/i);
    expect(box).toBeEnabled(); // data files selected via the template
    fireEvent.click(box);
    fireEvent.click(screen.getByRole("button", { name: /Start run/i }));

    const fetchMock = globalThis.fetch as unknown as ReturnType<typeof vi.fn>;
    await waitFor(() => {
      const call = fetchMock.mock.calls.find(
        (c: unknown[]) => String(c[0]).includes("/api/v1/runs") && (c[1] as RequestInit)?.method === "POST",
      );
      expect(call).toBeTruthy();
      const body = JSON.parse(String((call![1] as RequestInit).body));
      expect(body.refreshDataFiles).toBe(true);
      expect(body.dataFilesBlobId).toBe("data-1");
    });
  });
});
