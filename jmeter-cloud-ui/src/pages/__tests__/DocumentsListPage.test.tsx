import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { DocumentsListPage } from "../DocumentsListPage";
import type { Application } from "../../api/applications";
import type { BlobMetadata, BlobListing } from "../../api/blobs";

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
      list: vi.fn(),
      upload: vi.fn(),
      delete: vi.fn(),
      metadata: vi.fn(),
    },
  };
});

import { applicationsApi } from "../../api/applications";
import { blobsApi } from "../../api/blobs";
const apps  = applicationsApi as unknown as { list: ReturnType<typeof vi.fn> };
const blobs = blobsApi        as unknown as { list: ReturnType<typeof vi.fn> };

beforeEach(() => {
  apps.list.mockReset();
  blobs.list.mockReset();
});

function appA(): Application {
  return {
    applicationId: "01APPA",
    name: "alpha",
    sealId: null, description: null, healthEndpoints: [],
    metricsGroupId: "cps",
    createdAt: "2026-05-12T00:00:00Z",
  };
}
function appB(): Application {
  return {
    applicationId: "01APPB",
    name: "beta",
    sealId: null, description: null, healthEndpoints: [],
    metricsGroupId: "cps",
    createdAt: "2026-05-12T00:00:00Z",
  };
}
function blob(over: Partial<BlobMetadata> = {}): BlobMetadata {
  return {
    blobId: "01BLOB",
    sizeBytes: 100,
    sha256: "deadbeef",
    contentType: "application/octet-stream",
    uploadedAt: new Date().toISOString(),
    name: "x",
    type: "testPlan",
    application: "alpha",
    ...over,
  };
}
function listing(items: BlobMetadata[]): BlobListing {
  return { items, total: items.length, offset: 0, limit: 500 };
}

describe("DocumentsListPage — Phase IA-Documents", () => {
  it("renders one row per app with per-type aggregated counts", async () => {
    apps.list.mockResolvedValue([appA(), appB()]);
    blobs.list.mockResolvedValue(listing([
      blob({ blobId: "1", application: "alpha", type: "testPlan" }),
      blob({ blobId: "2", application: "alpha", type: "dataFiles" }),
      blob({ blobId: "3", application: "alpha", type: "result" }),
      blob({ blobId: "4", application: "beta",  type: "testPlan" }),
      // Untagged + template — both should be excluded from counts.
      blob({ blobId: "5", application: null,    type: "testPlan" }),
      blob({ blobId: "6", application: "alpha", type: "template" }),
    ]));

    render(<MemoryRouter><DocumentsListPage /></MemoryRouter>);

    expect(await screen.findByRole("link", { name: "alpha" })).toBeInTheDocument();

    const alphaRow = screen.getByRole("link", { name: "alpha" }).closest("tr")!;
    // Cells (post-rebuild): name | activity | testPlan | dataFiles | result | other | total
    expect(alphaRow.children[2]).toHaveTextContent("1"); // testPlan
    expect(alphaRow.children[3]).toHaveTextContent("1"); // dataFiles
    expect(alphaRow.children[4]).toHaveTextContent("1"); // result
    expect(alphaRow.children[5]).toHaveTextContent("0"); // other
    expect(alphaRow.children[6]).toHaveTextContent("3"); // total

    const betaRow = screen.getByRole("link", { name: "beta" }).closest("tr")!;
    expect(betaRow.children[6]).toHaveTextContent("1");  // total = just the testPlan
  });

  it("filters apps by name substring", async () => {
    apps.list.mockResolvedValue([appA(), appB()]);
    blobs.list.mockResolvedValue(listing([]));

    render(<MemoryRouter><DocumentsListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    fireEvent.change(screen.getByLabelText(/Filter applications by name/), { target: { value: "bet" } });

    await waitFor(() => {
      expect(screen.queryByRole("link", { name: "alpha" })).not.toBeInTheDocument();
      expect(screen.getByRole("link", { name: "beta" })).toBeInTheDocument();
    });
  });

  it("entire row is clickable as role=link with keyboard support", async () => {
    apps.list.mockResolvedValue([appA()]);
    blobs.list.mockResolvedValue(listing([]));

    render(<MemoryRouter><DocumentsListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    const row = screen.getByRole("link", { name: /Open documents for alpha/i });
    expect(row.tagName).toBe("TR");
  });

  it("sorts by Total when the column header is clicked", async () => {
    apps.list.mockResolvedValue([appA(), appB()]);
    blobs.list.mockResolvedValue(listing([
      blob({ blobId: "a1", application: "alpha", type: "testPlan" }),
      blob({ blobId: "b1", application: "beta",  type: "testPlan" }),
      blob({ blobId: "b2", application: "beta",  type: "dataFiles" }),
      blob({ blobId: "b3", application: "beta",  type: "result" }),
    ]));

    render(<MemoryRouter><DocumentsListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    fireEvent.click(screen.getByRole("button", { name: /Total/ }));

    await waitFor(() => {
      const links = screen.getAllByRole("link", { name: /^(alpha|beta)$/ });
      // beta has 3 docs; alpha has 1 — beta sorts first when desc.
      expect(links[0]).toHaveTextContent("beta");
    });
  });

  it("'/' keyboard shortcut focuses the search box", async () => {
    apps.list.mockResolvedValue([appA()]);
    blobs.list.mockResolvedValue(listing([]));

    render(<MemoryRouter><DocumentsListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    const search = screen.getByLabelText(/Filter applications by name/) as HTMLInputElement;
    expect(document.activeElement).not.toBe(search);

    fireEvent.keyDown(document.body, { key: "/" });

    expect(document.activeElement).toBe(search);
  });

  it("renders cross-app totals chips above the table", async () => {
    apps.list.mockResolvedValue([appA(), appB()]);
    blobs.list.mockResolvedValue(listing([
      blob({ blobId: "1", application: "alpha", type: "testPlan" }),
      blob({ blobId: "2", application: "beta",  type: "testPlan" }),
      blob({ blobId: "3", application: "alpha", type: "dataFiles" }),
    ]));

    render(<MemoryRouter><DocumentsListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    // `test plans` chip lives in the header summary; should show 2 across all apps.
    const chip = screen.getByText("test plans").closest("span.chip")!;
    expect(chip).toHaveTextContent("2");
  });
});
