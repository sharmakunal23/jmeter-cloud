import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { TemplatesListPage } from "../TemplatesListPage";
import type { Application } from "../../api/applications";
import type { TemplateSummary } from "../../api/templates";

vi.mock("../../api/applications", async () => {
  const actual = await vi.importActual<typeof import("../../api/applications")>("../../api/applications");
  return {
    ...actual,
    applicationsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
  };
});
vi.mock("../../api/templates", async () => {
  const actual = await vi.importActual<typeof import("../../api/templates")>("../../api/templates");
  return {
    ...actual,
    templatesApi: { list: vi.fn(), save: vi.fn(), load: vi.fn(), delete: vi.fn() },
  };
});

import { applicationsApi } from "../../api/applications";
import { templatesApi } from "../../api/templates";
const apps      = applicationsApi as unknown as { list: ReturnType<typeof vi.fn> };
const templates = templatesApi    as unknown as { list: ReturnType<typeof vi.fn> };

beforeEach(() => {
  apps.list.mockReset();
  templates.list.mockReset();
});

function appA(): Application {
  return {
    applicationId: "01APPA",
    name: "alpha",
    sealId: null, description: null, healthEndpoints: [],
    capacity: [{ region: "us-east", maxAvailable: 1 }],
    createdAt: "2026-05-12T00:00:00Z",
  };
}
function appB(): Application {
  return {
    applicationId: "01APPB",
    name: "beta",
    sealId: null, description: null, healthEndpoints: [],
    capacity: [{ region: "us-east", maxAvailable: 1 }],
    createdAt: "2026-05-12T00:00:00Z",
  };
}
function template(over: Partial<TemplateSummary> = {}): TemplateSummary {
  return {
    blobId: "01TPL",
    name: "smoke",
    application: "alpha",
    description: null,
    uploadedAt: new Date().toISOString(),
    sizeBytes: 512,
    ...over,
  };
}

describe("TemplatesListPage — Phase IA-Templates", () => {
  it("renders one row per app with per-app template counts", async () => {
    apps.list.mockResolvedValue([appA(), appB()]);
    templates.list.mockResolvedValue([
      template({ blobId: "1", application: "alpha" }),
      template({ blobId: "2", application: "alpha" }),
      template({ blobId: "3", application: "alpha" }),
      template({ blobId: "4", application: "beta" }),
      // Untagged template — should be excluded.
      template({ blobId: "5", application: "" as unknown as string }),
      // Tagged with an unknown app — should be excluded.
      template({ blobId: "6", application: "ghost" }),
    ]);

    render(<MemoryRouter><TemplatesListPage /></MemoryRouter>);

    expect(await screen.findByRole("link", { name: "alpha" })).toBeInTheDocument();

    const alphaRow = screen.getByRole("link", { name: "alpha" }).closest("tr")!;
    // Cells (post Last-Saved-drop polish): name | activity | count
    expect(alphaRow.children[2]).toHaveTextContent("3");

    const betaRow = screen.getByRole("link", { name: "beta" }).closest("tr")!;
    expect(betaRow.children[2]).toHaveTextContent("1");
  });

  it("filters apps by name substring", async () => {
    apps.list.mockResolvedValue([appA(), appB()]);
    templates.list.mockResolvedValue([]);

    render(<MemoryRouter><TemplatesListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    fireEvent.change(screen.getByLabelText(/Filter applications by name/), { target: { value: "bet" } });

    await waitFor(() => {
      expect(screen.queryByRole("link", { name: "alpha" })).not.toBeInTheDocument();
      expect(screen.getByRole("link", { name: "beta" })).toBeInTheDocument();
    });
  });

  it("entire row is clickable as role=link", async () => {
    apps.list.mockResolvedValue([appA()]);
    templates.list.mockResolvedValue([]);

    render(<MemoryRouter><TemplatesListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    const row = screen.getByRole("link", { name: /Open templates for alpha/i });
    expect(row.tagName).toBe("TR");
  });

  it("sorts by Templates count when the column header is clicked", async () => {
    apps.list.mockResolvedValue([appA(), appB()]);
    templates.list.mockResolvedValue([
      template({ blobId: "a1", application: "alpha" }),
      template({ blobId: "b1", application: "beta" }),
      template({ blobId: "b2", application: "beta" }),
      template({ blobId: "b3", application: "beta" }),
    ]);

    render(<MemoryRouter><TemplatesListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    fireEvent.click(screen.getByRole("button", { name: /Templates/ }));

    await waitFor(() => {
      const links = screen.getAllByRole("link", { name: /^(alpha|beta)$/ });
      // beta has 3; alpha has 1 — beta sorts first when desc.
      expect(links[0]).toHaveTextContent("beta");
    });
  });

  it("'/' keyboard shortcut focuses the search box", async () => {
    apps.list.mockResolvedValue([appA()]);
    templates.list.mockResolvedValue([]);

    render(<MemoryRouter><TemplatesListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    const search = screen.getByLabelText(/Filter applications by name/) as HTMLInputElement;
    expect(document.activeElement).not.toBe(search);

    fireEvent.keyDown(document.body, { key: "/" });

    expect(document.activeElement).toBe(search);
  });

  it("renders cross-app totals chip above the table", async () => {
    apps.list.mockResolvedValue([appA(), appB()]);
    templates.list.mockResolvedValue([
      template({ blobId: "1", application: "alpha" }),
      template({ blobId: "2", application: "beta" }),
      template({ blobId: "3", application: "alpha" }),
    ]);

    render(<MemoryRouter><TemplatesListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    const chip = screen.getByText("templates").closest("span.chip")!;
    expect(chip).toHaveTextContent("3");
  });

  it("Grid view renders one .appCard per app + clicking it navigates to the drill-in (via Link)", async () => {
    try { localStorage.setItem("jmeterCloud.templates.listViewMode", "grid"); } catch { /* ignore */ }
    apps.list.mockResolvedValue([appA(), appB()]);
    templates.list.mockResolvedValue([template({ application: "alpha" })]);

    render(<MemoryRouter><TemplatesListPage /></MemoryRouter>);

    const alphaCard = await screen.findByRole("link", { name: /Open templates for alpha/i });
    expect(alphaCard.className).toContain("appCard");
    expect(alphaCard).toHaveAttribute("href", "/templates/alpha");
    expect(screen.getByRole("link", { name: /Open templates for beta/i })).toBeInTheDocument();
  });

  it("clicking the View toggle persists the new mode to localStorage", async () => {
    try { localStorage.removeItem("jmeterCloud.templates.listViewMode"); } catch { /* ignore */ }
    apps.list.mockResolvedValue([appA()]);
    templates.list.mockResolvedValue([]);

    render(<MemoryRouter><TemplatesListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    fireEvent.click(screen.getByRole("tab", { name: "Grid" }));

    expect(localStorage.getItem("jmeterCloud.templates.listViewMode")).toBe("grid");
  });

  it("empty registry shows the 'register an application first' empty state", async () => {
    apps.list.mockResolvedValue([]);
    templates.list.mockResolvedValue([]);

    render(<MemoryRouter><TemplatesListPage /></MemoryRouter>);

    await waitFor(() => {
      expect(screen.getByText(/No applications registered/i)).toBeInTheDocument();
    });
    expect(screen.getByRole("link", { name: "Applications" })).toHaveAttribute("href", "/applications");
  });
});
