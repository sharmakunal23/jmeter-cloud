import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { axe } from "vitest-axe";

vi.mock("../../api/plugins", async () => {
  const actual = await vi.importActual<typeof import("../../api/plugins")>("../../api/plugins");
  return {
    ...actual,
    pluginsApi: { list: vi.fn(), create: vi.fn(), delete: vi.fn() },
  };
});
vi.mock("../../api/blobs", async () => {
  const actual = await vi.importActual<typeof import("../../api/blobs")>("../../api/blobs");
  return {
    ...actual,
    blobsApi: { ...actual.blobsApi, upload: vi.fn(), delete: vi.fn() },
  };
});

import { PluginApiError, pluginsApi, type PluginSummary } from "../../api/plugins";
import { blobsApi } from "../../api/blobs";
import { PluginsPage } from "../PluginsPage";

const listMock = pluginsApi.list as unknown as ReturnType<typeof vi.fn>;
const createMock = pluginsApi.create as unknown as ReturnType<typeof vi.fn>;
const deleteMock = pluginsApi.delete as unknown as ReturnType<typeof vi.fn>;
const uploadMock = blobsApi.upload as unknown as ReturnType<typeof vi.fn>;
const blobDeleteMock = blobsApi.delete as unknown as ReturnType<typeof vi.fn>;

const P1: PluginSummary = {
  pluginId: "p1", name: "jpgc-casutg", version: "3.1", sizeBytes: 2048,
  sha256: "a", fileName: "casutg.jar", createdAt: "2026-08-30T00:00:00Z", createdBy: "kunal",
};

function renderPage() {
  return render(<MemoryRouter><PluginsPage /></MemoryRouter>);
}

beforeEach(() => {
  listMock.mockReset().mockResolvedValue([P1]);
  createMock.mockReset();
  deleteMock.mockReset();
  uploadMock.mockReset();
  blobDeleteMock.mockReset();
});

describe("PluginsPage", () => {
  it("lists the library with name@version facts", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText("jpgc-casutg")).toBeInTheDocument());
    expect(screen.getByText("3.1")).toBeInTheDocument();
    expect(screen.getByText("casutg.jar")).toBeInTheDocument();
  });

  it("empty library renders the empty state", async () => {
    listMock.mockResolvedValue([]);
    renderPage();
    await waitFor(() => expect(screen.getByText(/No plugins yet/)).toBeInTheDocument());
  });

  it("add plugin runs the two steps in order: blob upload, then register", async () => {
    const order: string[] = [];
    uploadMock.mockImplementation(async () => { order.push("upload"); return { blobId: "b1" }; });
    createMock.mockImplementation(async () => { order.push("create"); return { ...P1, pluginId: "p9", name: "new-plugin", version: "1.0" }; });
    renderPage();
    await waitFor(() => expect(screen.getByText("jpgc-casutg")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /\+ Add plugin/ }));
    const dlg = within(screen.getByRole("dialog"));
    const file = new File(["PK\x03\x04"], "new-plugin.jar", { type: "application/java-archive" });
    fireEvent.change(dlg.getByLabelText(/File/), { target: { files: [file] } });
    fireEvent.change(dlg.getByLabelText(/Version/), { target: { value: "1.0" } });
    fireEvent.click(dlg.getByRole("button", { name: /^Add plugin$/ }));
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(order).toEqual(["upload", "create"]);
    expect(uploadMock.mock.calls[0][1]).toMatchObject({ type: "plugin", name: "new-plugin.jar" });
    expect(createMock.mock.calls[0][0]).toMatchObject({ name: "new-plugin", version: "1.0", blobId: "b1" });
    await waitFor(() => expect(screen.getByText(/Plugin new-plugin@1\.0 added/)).toBeInTheDocument());
  });

  it("409 NAME_TAKEN shows the existing version and the UI deletes no blob", async () => {
    uploadMock.mockResolvedValue({ blobId: "b1" });
    createMock.mockRejectedValue(new PluginApiError(409, "PLUGIN_NAME_TAKEN", "taken",
      { pluginId: "p1", name: "jpgc-casutg", version: "3.1" }));
    renderPage();
    await waitFor(() => expect(screen.getByText("jpgc-casutg")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /\+ Add plugin/ }));
    const dlg = within(screen.getByRole("dialog"));
    const file = new File(["x"], "jpgc-casutg.jar");
    fireEvent.change(dlg.getByLabelText(/File/), { target: { files: [file] } });
    fireEvent.change(dlg.getByLabelText(/Version/), { target: { value: "9.9" } });
    fireEvent.click(dlg.getByRole("button", { name: /^Add plugin$/ }));
    await waitFor(() =>
      expect(dlg.getByRole("alert")).toHaveTextContent(/already exists at v3\.1 — delete the existing plugin first/));
    expect(blobDeleteMock).not.toHaveBeenCalled();
  });

  it("delete goes through the confirm dialog", async () => {
    deleteMock.mockResolvedValue(undefined);
    renderPage();
    await waitFor(() => expect(screen.getByText("jpgc-casutg")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /delete plugin jpgc-casutg/ }));
    fireEvent.click(screen.getByRole("button", { name: /^Delete plugin$/ }));
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith("p1"));
  });

  it("has no axe violations", async () => {
    const { container } = renderPage();
    await waitFor(() => expect(screen.getByText("jpgc-casutg")).toBeInTheDocument());
    expect(await axe(container)).toHaveNoViolations();
  });
});
