import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { DocumentsDetailPage } from "../DocumentsDetailPage";
import type { Application } from "../../api/applications";

vi.mock("../../api/applications", async () => {
  const actual = await vi.importActual<typeof import("../../api/applications")>("../../api/applications");
  return {
    ...actual,
    applicationsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
  };
});
vi.mock("../../api/blobs", async () => {
  const actual = await vi.importActual<typeof import("../../api/blobs")>("../../api/blobs");
  return {
    ...actual,
    blobsApi: {
      list: vi.fn().mockResolvedValue({ items: [], total: 0, offset: 0, limit: 25 }),
      upload: vi.fn(),
      delete: vi.fn(),
      metadata: vi.fn(),
    },
  };
});

import { applicationsApi } from "../../api/applications";
const apps = applicationsApi as unknown as { list: ReturnType<typeof vi.fn> };

beforeEach(() => apps.list.mockReset());

function fixtureApp(): Application {
  return {
    applicationId: "01CHK",
    name: "checkout",
    sealId: null, description: null, healthEndpoints: [],
    capacity: [{ region: "us-east", maxAvailable: 1 }],
    createdAt: "2026-05-12T00:00:00Z",
  };
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/documents"                      element={<div>documents-list-stub</div>} />
        <Route path="/documents/:appName"             element={<DocumentsDetailPage />} />
        <Route path="/documents/:appName/:type"       element={<DocumentsDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("DocumentsDetailPage — Phase IA-Documents", () => {
  it("renders the per-app detail with 4 tabs + nav links to Application + Run launcher", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);

    renderAt("/documents/checkout");

    await screen.findByRole("link", { name: /Open Application/ });
    expect(screen.getByRole("link", { name: /Open Application/ })).toHaveAttribute(
      "href", "/applications/checkout",
    );
    expect(screen.getByRole("link", { name: /Launch a Run/ })).toHaveAttribute(
      "href", "/applications/checkout/runs/new",
    );

    // Four tab links exist.
    expect(screen.getByRole("tab", { name: "Test Plans" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Data Files" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Results" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Other" })).toBeInTheDocument();
  });

  it("legacy /documents/{type} URL redirects to /documents (list page)", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);

    renderAt("/documents/testPlan");

    await waitFor(() => {
      // The Routes resolves /documents → the stub.
      expect(screen.getByText("documents-list-stub")).toBeInTheDocument();
    });
  });

  it("Test Plans tab is visually active when navigating to /documents/:appName (no :type)", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);

    renderAt("/documents/checkout");

    // The default route /documents/:appName implicitly shows the
    // testPlan tab. Bug fix: without an explicit :type segment in the
    // URL, the NavLink's isActive returned false for every tab, leaving
    // them all unhighlighted. We now compute the active state ourselves.
    const testPlansTab = await screen.findByRole("tab", { name: "Test Plans" });
    expect(testPlansTab).toHaveAttribute("aria-selected", "true");
    expect(testPlansTab.className).toMatch(/documentsTabs__tab--active/);

    // Sibling tabs are explicitly NOT active.
    expect(screen.getByRole("tab", { name: "Data Files" })).toHaveAttribute("aria-selected", "false");
    expect(screen.getByRole("tab", { name: "Results" })).toHaveAttribute("aria-selected", "false");
    expect(screen.getByRole("tab", { name: "Other" })).toHaveAttribute("aria-selected", "false");
  });

  it("explicitly selecting a tab via the URL marks the right one active", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);

    renderAt("/documents/checkout/dataFiles");

    const dataFilesTab = await screen.findByRole("tab", { name: "Data Files" });
    expect(dataFilesTab).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "Test Plans" })).toHaveAttribute("aria-selected", "false");
  });

  it("notFound state shows when the appName isn't a registered app", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);

    renderAt("/documents/does-not-exist");

    await screen.findByText(/not found/i);
  });
});
