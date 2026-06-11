import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { ApplicationsListPage } from "../ApplicationsListPage";
import type { Application } from "../../api/applications";

vi.mock("../../api/applications", async () => {
  const actual = await vi.importActual<typeof import("../../api/applications")>("../../api/applications");
  return {
    ...actual,
    applicationsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
  };
});
vi.mock("../../api/runs", async () => {
  const actual = await vi.importActual<typeof import("../../api/runs")>("../../api/runs");
  return {
    ...actual,
    runsApi: { ...actual.runsApi, listPage: vi.fn() },
  };
});

import { applicationsApi } from "../../api/applications";
import { runsApi } from "../../api/runs";

const apps = applicationsApi as unknown as {
  list: ReturnType<typeof vi.fn>;
  create: ReturnType<typeof vi.fn>;
};
const runs = runsApi as unknown as { listPage: ReturnType<typeof vi.fn> };

beforeEach(() => {
  apps.list.mockReset();
  apps.create.mockReset();
  runs.listPage.mockReset();
  runs.listPage.mockResolvedValue({ runs: [], total: 0, offset: 0, limit: 200 });
  // Reset the persisted view-mode between tests so each starts at the
  // default (list, per D4 polish).
  try { localStorage.removeItem("jmeterCloud.applications.viewMode"); } catch { /* ignore */ }
});

function renderPage() {
  return render(
    <MemoryRouter>
      <ApplicationsListPage />
    </MemoryRouter>,
  );
}

function fixtureApp(name: string, overrides: Partial<Application> = {}): Application {
  return {
    applicationId: `01J0${name}AAAAAAAAAAAAAAAAAA`.slice(0, 26),
    name,
    sealId: null,
    description: null,
    healthEndpoints: [],
    createdAt: "2026-05-11T12:00:00Z",
    lastHealthCheckedAt: null,
    lastHealthStatus: "UNKNOWN",
    lastHealthDetails: null,
    ...overrides,
  };
}

describe("ApplicationsListPage — registry rendering", () => {
  it("renders one row per application (default list view)", async () => {
    apps.list.mockResolvedValue([fixtureApp("checkout-svc"), fixtureApp("payment-api")]);
    renderPage();
    await waitFor(() => expect(screen.getByText("checkout-svc")).toBeInTheDocument());
    expect(screen.getByText("payment-api")).toBeInTheDocument();
    // List view renders a table; cards (role=listitem) only appear in grid view.
    expect(screen.getByRole("columnheader", { name: "Health" })).toBeInTheDocument();
    expect(screen.queryAllByRole("listitem")).toHaveLength(0);
  });

  it("each card link uses the URL-encoded name as the path segment", async () => {
    apps.list.mockResolvedValue([fixtureApp("svc/v2 beta")]);
    renderPage();
    await waitFor(() => {
      const link = screen.getByRole("link", { name: /svc\/v2 beta/ }) as HTMLAnchorElement;
      expect(link.getAttribute("href")).toBe("/applications/svc%2Fv2%20beta");
    });
  });

  it("empty registry renders the 'no applications yet' empty-state CTA", async () => {
    apps.list.mockResolvedValue([]);
    renderPage();
    await waitFor(() => expect(screen.getByText(/No applications registered yet/i)).toBeInTheDocument());
    expect(screen.getByRole("button", { name: /Register your first application/i })).toBeInTheDocument();
  });
});

describe("ApplicationsListPage — view-mode toggle", () => {
  it("renders Grid + List tabs; List is default + active", async () => {
    apps.list.mockResolvedValue([fixtureApp("a")]);
    renderPage();
    await waitFor(() => expect(screen.getByRole("tab", { name: "Grid" })).toBeInTheDocument());
    expect(screen.getByRole("tab", { name: "List" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "Grid" })).toHaveAttribute("aria-selected", "false");
  });

  it("clicking Grid swaps in the card view (cards rendered as listitems)", async () => {
    apps.list.mockResolvedValue([fixtureApp("a")]);
    renderPage();
    await waitFor(() => expect(screen.getByRole("tab", { name: "Grid" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("tab", { name: "Grid" }));
    expect(screen.getAllByRole("listitem").length).toBeGreaterThan(0);
    expect(screen.queryByRole("columnheader", { name: "Health" })).toBeNull();
  });

  it("Grid view persists across re-mounts via localStorage", async () => {
    apps.list.mockResolvedValue([fixtureApp("a")]);
    const utils = renderPage();
    await waitFor(() => expect(screen.getByRole("tab", { name: "Grid" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("tab", { name: "Grid" }));
    utils.unmount();

    apps.list.mockResolvedValue([fixtureApp("a")]);
    runs.listPage.mockResolvedValue({ runs: [], total: 0, offset: 0, limit: 200 });
    renderPage();
    await waitFor(() => expect(screen.getAllByRole("listitem").length).toBeGreaterThan(0));
  });
});

describe("ApplicationsListPage — health badge", () => {
  it("HEALTHY app shows a HEALTHY badge", async () => {
    apps.list.mockResolvedValue([
      fixtureApp("checkout-svc", { lastHealthStatus: "HEALTHY", lastHealthCheckedAt: "2026-05-11T12:00:00Z" }),
    ]);
    renderPage();
    await waitFor(() => {
      const badge = document.querySelector('.healthBadge--healthy');
      expect(badge).not.toBeNull();
      expect(badge).toHaveTextContent("HEALTHY");
    });
  });

  it("DEGRADED app shows a DEGRADED badge", async () => {
    apps.list.mockResolvedValue([
      fixtureApp("checkout-svc", { lastHealthStatus: "DEGRADED" }),
    ]);
    renderPage();
    await waitFor(() => {
      expect(document.querySelector('.healthBadge--degraded')).toHaveTextContent("DEGRADED");
    });
  });

  it("missing/null status falls back to UNKNOWN", async () => {
    apps.list.mockResolvedValue([
      fixtureApp("checkout-svc", { lastHealthStatus: null }),
    ]);
    renderPage();
    await waitFor(() => {
      expect(document.querySelector('.healthBadge--unknown')).toHaveTextContent("UNKNOWN");
    });
  });
});

describe("ApplicationsListPage — Create dialog", () => {
  it("clicking + Register opens the modal dialog", async () => {
    apps.list.mockResolvedValue([fixtureApp("a")]);
    renderPage();
    await waitFor(() => expect(screen.getByRole("button", { name: /Register application/i })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /Register application/i }));
    expect(screen.getByRole("dialog", { name: /Register a new application/i })).toBeInTheDocument();
  });

  it("submitting the dialog calls applicationsApi.create + closes on success", async () => {
    apps.list.mockResolvedValue([]);
    apps.create.mockResolvedValue(fixtureApp("brand-new"));
    renderPage();
    await waitFor(() => expect(screen.getByRole("button", { name: /Register your first application/i })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /Register your first application/i }));
    fireEvent.change(screen.getByLabelText(/Name \*/i), { target: { value: "brand-new" } });
    // D-Capacity v2 polish — capacity is sponsor-controlled; the form
    // doesn't collect it, so a name alone is enough to submit.
    fireEvent.click(screen.getByRole("button", { name: /^Register$/i }));
    await waitFor(() => expect(apps.create).toHaveBeenCalledWith(expect.objectContaining({
      name: "brand-new",
    })));
    // Dialog closes after success.
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });
});
