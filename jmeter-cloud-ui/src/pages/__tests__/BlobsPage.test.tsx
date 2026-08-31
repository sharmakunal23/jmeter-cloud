import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { BlobsPage } from "../BlobsPage";
import type { BlobListing, BlobMetadata } from "../../api/blobs";

vi.mock("../../api/applications", async () => {
  const actual = await vi.importActual<typeof import("../../api/applications")>("../../api/applications");
  return {
    ...actual,
    applicationsApi: { list: vi.fn().mockResolvedValue([]), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
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
      deleteMany: vi.fn(),
      metadata: vi.fn(),
    },
  };
});

import { blobsApi } from "../../api/blobs";
const api = blobsApi as unknown as {
  list: ReturnType<typeof vi.fn>;
  deleteMany: ReturnType<typeof vi.fn>;
};

function blob(blobId: string, name: string, over: Partial<BlobMetadata> = {}): BlobMetadata {
  return {
    blobId,
    name,
    sizeBytes: 2048,
    sha256: "0".repeat(64),
    uploadedAt: "2026-08-31T10:00:00Z",
    type: "testPlan",
    application: "checkout",
    ...over,
  };
}

function listing(items: BlobMetadata[]): BlobListing {
  return { items, total: items.length, offset: 0, limit: 10 };
}

const PLAN_A = blob("01AAA", "checkoutSmoke.jmx");
const PLAN_B = blob("01BBB", "checkoutSoak.jmx");

function renderPage() {
  return render(
    <MemoryRouter>
      <BlobsPage pinnedApplication="checkout" pinnedType="testPlan" hideHeader />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  api.list.mockReset().mockResolvedValue(listing([PLAN_A, PLAN_B]));
  api.deleteMany.mockReset().mockResolvedValue({ deleted: ["01AAA", "01BBB"], failed: [] });
});

describe("BlobsPage — bulk selection", () => {
  it("selects every row on the page from the header checkbox and reports the count", async () => {
    renderPage();

    await screen.findByText("checkoutSmoke.jmx");
    // Nothing selected → no bulk bar.
    expect(screen.queryByRole("toolbar", { name: /bulk actions/i })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("checkbox", { name: /select all documents on this page/i }));

    const bar = screen.getByRole("toolbar", { name: /bulk actions/i });
    expect(within(bar).getByText("2 selected")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Select checkoutSmoke.jmx (01AAA)" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Select checkoutSoak.jmx (01BBB)" })).toBeChecked();
  });

  it("marks the header checkbox indeterminate when only some rows are picked", async () => {
    renderPage();

    await screen.findByText("checkoutSmoke.jmx");
    fireEvent.click(screen.getByRole("checkbox", { name: "Select checkoutSmoke.jmx (01AAA)" }));

    const header = screen.getByRole("checkbox", { name: /select all documents on this page/i });
    expect(header).not.toBeChecked();
    expect((header as HTMLInputElement).indeterminate).toBe(true);

    // …and clears again on the way back — the inline ref callback re-runs only
    // because its identity changes every render.
    fireEvent.click(screen.getByRole("checkbox", { name: "Select checkoutSmoke.jmx (01AAA)" }));
    expect((header as HTMLInputElement).indeterminate).toBe(false);

    // Selecting the rest promotes it to fully checked, never indeterminate.
    fireEvent.click(screen.getByRole("checkbox", { name: "Select checkoutSmoke.jmx (01AAA)" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Select checkoutSoak.jmx (01BBB)" }));
    expect(header).toBeChecked();
    expect((header as HTMLInputElement).indeterminate).toBe(false);
  });

  it("bulk delete confirms in a modal, deletes the picked ids, then clears + refreshes", async () => {
    renderPage();

    await screen.findByText("checkoutSmoke.jmx");
    fireEvent.click(screen.getByRole("checkbox", { name: /select all documents on this page/i }));
    fireEvent.click(screen.getByRole("button", { name: "Delete selected" }));

    // The dialog names every file it is about to remove.
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/Delete 2 documents\?/)).toBeInTheDocument();
    expect(within(dialog).getByText("checkoutSmoke.jmx")).toBeInTheDocument();
    expect(within(dialog).getByText("checkoutSoak.jmx")).toBeInTheDocument();

    api.list.mockResolvedValue(listing([]));
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete 2" }));

    await waitFor(() => expect(api.deleteMany).toHaveBeenCalledWith(["01AAA", "01BBB"]));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    // Success is a toast, and the selection is gone with the rows.
    expect(await screen.findByText("2 documents deleted.")).toBeInTheDocument();
    expect(screen.queryByRole("toolbar", { name: /bulk actions/i })).not.toBeInTheDocument();
  });

  it("keeps the dialog open and names the blobs that survived a partial failure", async () => {
    api.deleteMany.mockResolvedValue({
      deleted: ["01AAA"],
      failed: [{ blobId: "01BBB", message: "HTTP_500: backend I/O failure" }],
    });
    renderPage();

    await screen.findByText("checkoutSmoke.jmx");
    fireEvent.click(screen.getByRole("checkbox", { name: /select all documents on this page/i }));
    fireEvent.click(screen.getByRole("button", { name: "Delete selected" }));

    const dialog = await screen.findByRole("dialog");
    api.list.mockResolvedValue(listing([PLAN_B]));
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete 2" }));

    await screen.findByText(/1 document could not be deleted/);
    expect(screen.getByText(/backend I\/O failure/)).toBeInTheDocument();
    // The dialog narrows to the survivor, so confirming again retries only it.
    expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Delete 1" })).toBeInTheDocument();
    expect(within(screen.getByRole("dialog")).queryByText("checkoutSmoke.jmx")).not.toBeInTheDocument();
    // The one that went is dropped from the selection; the failure stays armed.
    expect(await screen.findByText("1 selected")).toBeInTheDocument();
  });

  it("a row's own Delete button confirms in the same modal, scoped to that file", async () => {
    renderPage();

    await screen.findByText("checkoutSmoke.jmx");
    const row = screen.getByText("checkoutSmoke.jmx").closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: "Delete" }));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/Delete 1 document\?/)).toBeInTheDocument();
    expect(within(dialog).queryByText("checkoutSoak.jmx")).not.toBeInTheDocument();

    api.deleteMany.mockResolvedValue({ deleted: ["01AAA"], failed: [] });
    api.list.mockResolvedValue(listing([PLAN_B]));
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete 1" }));

    await waitFor(() => expect(api.deleteMany).toHaveBeenCalledWith(["01AAA"]));
  });

  it("warns that deleting a run result shrinks that run's results zip", async () => {
    api.list.mockResolvedValue(listing([blob("01RRR", "results-01RUN-worker1.jtl.gz", { type: "result" })]));
    renderPage();

    // The table shows the download filename, which swaps "-" for "_" to match
    // what the browser actually saves (BlobController#inferDownloadFilename).
    await screen.findByText("results_01RUN_worker1.jtl.gz");
    fireEvent.click(screen.getByRole("checkbox", { name: /select all documents on this page/i }));
    fireEvent.click(screen.getByRole("button", { name: "Delete selected" }));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/Download results/)).toBeInTheDocument();
  });

  it("drops the selection when the listing scope changes", async () => {
    render(
      <MemoryRouter>
        <BlobsPage pinnedApplication="checkout" hideHeader />
      </MemoryRouter>,
    );

    await screen.findByText("checkoutSmoke.jmx");
    fireEvent.click(screen.getByRole("checkbox", { name: "Select checkoutSmoke.jmx (01AAA)" }));
    expect(screen.getByText("1 selected")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/Type:/), { target: { value: "dataFiles" } });

    await waitFor(() =>
      expect(screen.queryByRole("toolbar", { name: /bulk actions/i })).not.toBeInTheDocument(),
    );
  });
});
