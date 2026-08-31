import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Navigate, Route, Routes, useParams } from "react-router-dom";

import { CapacitySection } from "../CapacitySection";
import { CapacityListPage } from "../CapacityListPage";
import { ClustersPage } from "../ClustersPage";
import type { ClusterStatus } from "../../api/clusters";

/**
 * Capacity is one section with two tabs (2026-08-31): Reservations at
 * `/capacity` and Clusters at `/capacity/clusters`. Asserts the shell owns
 * the single `<h1>`, each tab renders its own body, and both old URLs
 * (`/clusters`, `/capacity/{groupId}`) still land where they moved to.
 */

vi.mock("../../api/applicationGroups", async () => {
  const actual = await vi.importActual<typeof import("../../api/applicationGroups")>("../../api/applicationGroups");
  return { ...actual, applicationGroupsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() } };
});
vi.mock("../../api/capacity", async () => {
  const actual = await vi.importActual<typeof import("../../api/capacity")>("../../api/capacity");
  return { ...actual, capacityApi: { listPods: vi.fn(), reconcileWorkers: vi.fn() } };
});
vi.mock("../../api/clusters", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/clusters")>();
  return {
    ...actual,
    clustersApi: { status: vi.fn(), register: vi.fn(), update: vi.fn(), remove: vi.fn(), testProvision: vi.fn() },
  };
});

import { applicationGroupsApi } from "../../api/applicationGroups";
import { clustersApi } from "../../api/clusters";

const apps = vi.mocked(applicationGroupsApi);
const clusters = vi.mocked(clustersApi);

function cluster(): ClusterStatus {
  return {
    region: "na-east",
    label: "na-east DC",
    regionalUrl: "http://na-east-control-plane:30088",
    maxWorkers: 20,
    reservedWorkers: 4,
    provisionedWorkers: 2,
    reachable: true,
    lastValidatedAt: "2026-08-31T00:00:00Z",
    lastProbe: null,
    probing: false,
  };
}

/** The capacity slice of the real route table (see src/App.tsx). */
function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="capacity" element={<CapacitySection />}>
          <Route index element={<CapacityListPage />} />
          <Route path="clusters" element={<ClustersPage />} />
        </Route>
        <Route path="capacity/groups/:groupId" element={<GroupStub />} />
        <Route path="clusters" element={<Navigate to="/capacity/clusters" replace />} />
        <Route path="capacity/:groupId" element={<LegacyGroupRedirect />} />
      </Routes>
    </MemoryRouter>,
  );
}

function GroupStub() {
  const { groupId } = useParams();
  return <div>group-detail:{groupId}</div>;
}
function LegacyGroupRedirect() {
  const { groupId } = useParams();
  return <Navigate to={`/capacity/groups/${encodeURIComponent(groupId ?? "")}`} replace />;
}

beforeEach(() => {
  vi.clearAllMocks();
  apps.list.mockResolvedValue([]);
  clusters.status.mockResolvedValue([cluster()]);
});

describe("Capacity section — two tabs under one heading", () => {
  it("/capacity shows the section heading with Reservations active", async () => {
    renderAt("/capacity");
    expect(screen.getByRole("heading", { name: "Capacity", level: 1 })).toBeInTheDocument();
    const tabs = screen.getByRole("navigation", { name: "Capacity views" });
    expect(within(tabs).getByRole("link", { name: "Reservations" })).toHaveClass("active");
    expect(within(tabs).getByRole("link", { name: "Clusters" })).not.toHaveClass("active");
    // The Reservations body is the group list.
    await waitFor(() => expect(apps.list).toHaveBeenCalled());
  });

  it("/capacity/clusters shows the registry under the same heading, Clusters active", async () => {
    renderAt("/capacity/clusters");
    expect(screen.getByRole("heading", { name: "Capacity", level: 1 })).toBeInTheDocument();
    // Exactly one <h1> — the tab bodies no longer carry their own.
    expect(document.querySelectorAll("h1")).toHaveLength(1);
    const tabs = screen.getByRole("navigation", { name: "Capacity views" });
    expect(within(tabs).getByRole("link", { name: "Clusters" })).toHaveClass("active");
    expect(await screen.findByText("na-east DC")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "+ Add cluster" })).toBeInTheDocument();
  });

  it("the old /clusters URL lands on the Clusters tab", async () => {
    renderAt("/clusters");
    expect(await screen.findByText("na-east DC")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Capacity", level: 1 })).toBeInTheDocument();
  });

  it("the old /capacity/{groupId} URL lands on the group drill-in", () => {
    renderAt("/capacity/servicing_mq");
    expect(screen.getByText("group-detail:servicing_mq")).toBeInTheDocument();
  });

  it("a group whose id is 'clusters' no longer shadows the Clusters tab", async () => {
    renderAt("/capacity/groups/clusters");
    expect(screen.getByText("group-detail:clusters")).toBeInTheDocument();
    // …and the tab itself still resolves to the registry.
    renderAt("/capacity/clusters");
    expect(await screen.findByText("na-east DC")).toBeInTheDocument();
  });
});
