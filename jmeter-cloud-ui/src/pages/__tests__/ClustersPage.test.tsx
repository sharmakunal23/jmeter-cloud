import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { ClustersPage } from "../ClustersPage";
import { ClusterApiError, clustersApi, type ClusterStatus } from "../../api/clusters";
import { __resetPlatformCapabilitiesCache } from "../../hooks/usePlatformCapabilities";

vi.mock("../../api/clusters", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/clusters")>();
  return {
    ...actual,
    clustersApi: {
      status: vi.fn(),
      register: vi.fn(),
      update: vi.fn(),
      remove: vi.fn(),
      testProvision: vi.fn(),
    },
  };
});

const api = vi.mocked(clustersApi);

function cluster(region: string, over: Partial<ClusterStatus> = {}): ClusterStatus {
  return {
    region,
    label: `${region} DC`,
    regionalUrl: `http://${region}-control-plane:30088`,
    maxWorkers: 20,
    reservedWorkers: 12,
    provisionedWorkers: 7,
    reachable: true,
    lastValidatedAt: new Date().toISOString(),
    lastProbe: { at: new Date().toISOString(), status: "PASS", detail: "ready in 9 s" },
    probing: false,
    ...over,
  };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/capacity/clusters"]}>
      <ClustersPage />
    </MemoryRouter>,
  );
}

describe("ClustersPage (CLUSTER-CAPACITY)", () => {
  beforeEach(() => {
    __resetPlatformCapabilitiesCache();
    api.status.mockResolvedValue([cluster("na-east"), cluster("na-west", { reachable: false, lastError: "connect refused", lastProbe: null })]);
  });
  afterEach(() => vi.clearAllMocks());

  it("lists registered clusters with health, worker counts, reservations and the probe verdict", async () => {
    renderPage();
    expect(await screen.findByText("na-east DC")).toBeInTheDocument();
    const east = screen.getByText("na-east DC").closest("tr")!;
    expect(within(east).getByText(/Reachable/)).toBeInTheDocument();
    expect(within(east).getByText("7/20")).toBeInTheDocument();
    expect(within(east).getByText("12/20")).toBeInTheDocument();
    expect(within(east).getByText(/PASS/)).toBeInTheDocument();
    const west = screen.getByText("na-west DC").closest("tr")!;
    expect(within(west).getByText("Unreachable")).toHaveAttribute("title", "connect refused");
    expect(within(west).getByText("never run")).toBeInTheDocument();
  });

  it("registers a cluster through the validated add flow", async () => {
    api.register.mockResolvedValue({ cluster: cluster("na-south"), checks: [] });
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /\+ Add cluster/ }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Cluster id"), { target: { value: "na-south" } });
    fireEvent.change(within(dialog).getByLabelText("Display name"), { target: { value: "NA South" } });
    fireEvent.change(within(dialog).getByLabelText("Regional orchestrator URL"),
      { target: { value: "http://na-south:30088" } });
    fireEvent.click(within(dialog).getByRole("button", { name: /Validate and add/ }));

    await waitFor(() => expect(api.register).toHaveBeenCalledWith({
      region: "na-south", label: "NA South", regionalUrl: "http://na-south:30088", maxWorkers: 20,
    }));
    await waitFor(() =>
      expect(screen.getByText(/registered — every validation check passed/)).toBeInTheDocument());
  });

  it("a failed validation renders the ✓/✗ checklist with the failing check's detail", async () => {
    api.register.mockRejectedValue(new ClusterApiError(422, "REGION_MISMATCH",
      "this cluster's regional reports region 'na-west', not 'na-south'",
      [
        { name: "endpointReachable", ok: true, detail: "regional orchestrator answered", code: "CLUSTER_UNREACHABLE" },
        { name: "regionMatches", ok: false, detail: "reports region 'na-west', not 'na-south'", code: "REGION_MISMATCH" },
      ]));
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /\+ Add cluster/ }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Cluster id"), { target: { value: "na-south" } });
    fireEvent.change(within(dialog).getByLabelText("Display name"), { target: { value: "NA South" } });
    fireEvent.change(within(dialog).getByLabelText("Regional orchestrator URL"),
      { target: { value: "http://na-west:30088" } });
    fireEvent.click(within(dialog).getByRole("button", { name: /Validate and add/ }));

    const checklist = await within(dialog).findByRole("list", { name: "Validation checks" });
    expect(within(checklist).getByText("Regional orchestrator reachable")).toBeInTheDocument();
    expect(within(checklist).getByText("Cluster id matches the regional")).toBeInTheDocument();
    expect(within(checklist).getByText(/reports region 'na-west', not 'na-south'/)).toBeInTheDocument();
    // The dialog stays open so the operator can fix the input.
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("starts an async test-provisioning probe and reports 409 PROBE_IN_PROGRESS honestly", async () => {
    api.testProvision.mockResolvedValueOnce({ region: "na-east", probing: true });
    renderPage();
    const east = (await screen.findByText("na-east DC")).closest("tr")!;
    fireEvent.click(within(east).getByRole("button", { name: "Test provisioning" }));
    await waitFor(() => expect(api.testProvision).toHaveBeenCalledWith("na-east"));
    expect(await screen.findByText(/Probing na-east DC/)).toBeInTheDocument();
  });

  it("removal confirms in a dialog and surfaces CLUSTER_IN_USE as a toast", async () => {
    api.remove.mockRejectedValue(new ClusterApiError(409, "CLUSTER_IN_USE",
      "cluster 'na-east' still holds 2 group reservation(s) and 7 worker(s); remove those first"));
    renderPage();
    const east = (await screen.findByText("na-east DC")).closest("tr")!;
    fireEvent.click(within(east).getByRole("button", { name: "Remove" }));
    const confirm = await screen.findByRole("dialog");
    fireEvent.click(within(confirm).getByRole("button", { name: /Remove cluster/ }));
    await waitFor(() => expect(api.remove).toHaveBeenCalledWith("na-east"));
    expect(await screen.findByText(/still holds 2 group reservation/)).toBeInTheDocument();
  });

  it("with no clusters registered it explains the deploy-then-register flow", async () => {
    api.status.mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText(/No clusters registered yet/)).toBeInTheDocument();
    expect(screen.getByText(/registration validates the endpoint/)).toBeInTheDocument();
  });
});
