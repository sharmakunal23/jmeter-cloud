import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { TemplatesDetailPage } from "../TemplatesDetailPage";
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
const templates = templatesApi    as unknown as {
  list: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
};

beforeEach(() => {
  apps.list.mockReset();
  templates.list.mockReset();
  templates.delete.mockReset();
  // Default to list view so the table renders deterministically (the toggle
  // persists across runs via localStorage).
  try { localStorage.setItem("jmeterCloud.templates.viewMode", "list"); } catch { /* ignore */ }
});

function fixtureApp(): Application {
  return {
    applicationId: "01CHK",
    name: "checkout",
    sealId: null, description: null, healthEndpoints: [],
    metricsGroupId: "cps",
    createdAt: "2026-05-12T00:00:00Z",
  };
}
function template(over: Partial<TemplateSummary> = {}): TemplateSummary {
  return {
    blobId: "01TPL",
    name: "smoke",
    application: "checkout",
    description: null,
    uploadedAt: "2026-05-12T12:00:00Z",
    sizeBytes: 512,
    ...over,
  };
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/templates"          element={<div>templates-list-stub</div>} />
        <Route path="/templates/:appName" element={<TemplatesDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("TemplatesDetailPage — Phase IA-Templates", () => {
  it("renders the per-app detail with header nav links + Templates list", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    templates.list.mockResolvedValue([template({ blobId: "T1", name: "smoke-checkout" })]);

    renderAt("/templates/checkout");

    await screen.findByRole("link", { name: /Open Application/ });
    expect(screen.getByRole("link", { name: /Open Application/ })).toHaveAttribute(
      "href", "/applications/checkout",
    );
    expect(screen.getByRole("link", { name: /Launch a Run/ })).toHaveAttribute(
      "href", "/applications/checkout/runs/new",
    );

    // Body shows the template row.
    expect(await screen.findByText("smoke-checkout")).toBeInTheDocument();
    // "Use →" deep-links into the launcher with the template query param.
    expect(screen.getByRole("link", { name: /Use/ })).toHaveAttribute(
      "href", "/applications/checkout/runs/new?template=T1",
    );
  });

  it("scopes the template list to the current app (filters out others)", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    templates.list.mockResolvedValue([
      template({ blobId: "T1", name: "checkout-smoke", application: "checkout" }),
      template({ blobId: "T2", name: "search-smoke",   application: "search" }),
    ]);

    renderAt("/templates/checkout");

    expect(await screen.findByText("checkout-smoke")).toBeInTheDocument();
    expect(screen.queryByText("search-smoke")).not.toBeInTheDocument();
  });

  it("notFound state shows when the appName isn't a registered app", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    templates.list.mockResolvedValue([]);

    renderAt("/templates/does-not-exist");

    await screen.findByText(/not found/i);
  });

  it("Delete opens a centered modal — confirming calls templatesApi.delete + refetches", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    // First call: 2 templates. Second call after delete: 1 left.
    templates.list
      .mockResolvedValueOnce([
        template({ blobId: "T1", name: "first" }),
        template({ blobId: "T2", name: "second" }),
      ])
      .mockResolvedValueOnce([
        template({ blobId: "T2", name: "second" }),
      ]);
    templates.delete.mockResolvedValue(undefined);

    renderAt("/templates/checkout");

    await screen.findByText("first");

    // Click the Delete button on the first template row.
    const deleteButtons = screen.getAllByRole("button", { name: /^Delete$/ });
    fireEvent.click(deleteButtons[0]);

    // Modal opens; heading is "Delete template?"
    expect(await screen.findByRole("heading", { name: /Delete template/i })).toBeInTheDocument();

    // Confirm deletion via the modal's Delete footer button.
    const modalDeleteBtn = screen.getAllByRole("button", { name: /^Delete$/ }).find(
      (b) => b.closest(".modal__footer") !== null,
    )!;
    fireEvent.click(modalDeleteBtn);

    await waitFor(() => {
      expect(templates.delete).toHaveBeenCalledWith("T1");
    });

    // After refetch, "first" is gone and "second" remains.
    await waitFor(() => {
      expect(screen.queryByText("first")).not.toBeInTheDocument();
      expect(screen.getByText("second")).toBeInTheDocument();
    });
  });

  it("Cancelling the delete modal leaves the template intact", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    templates.list.mockResolvedValue([template({ blobId: "T1", name: "first" })]);

    renderAt("/templates/checkout");
    await screen.findByText("first");

    fireEvent.click(screen.getByRole("button", { name: /^Delete$/ }));
    expect(await screen.findByRole("heading", { name: /Delete template/i })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    await waitFor(() => {
      expect(screen.queryByRole("heading", { name: /Delete template/i })).not.toBeInTheDocument();
    });
    expect(templates.delete).not.toHaveBeenCalled();
  });

  it("Empty per-app state shows the 'open Launch a Run + Save Template' guidance", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    templates.list.mockResolvedValue([]);

    renderAt("/templates/checkout");

    await waitFor(() => {
      expect(screen.getByText(/No templates saved for/i)).toBeInTheDocument();
    });
    expect(screen.getByText(/Save Template/i)).toBeInTheDocument();
  });
});
