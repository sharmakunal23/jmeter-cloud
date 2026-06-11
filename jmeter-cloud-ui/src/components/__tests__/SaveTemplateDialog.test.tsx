import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { SaveTemplateDialog } from "../SaveTemplateDialog";
import type { TemplateBody } from "../../api/templates";

vi.mock("../../api/templates", async () => {
  const actual = await vi.importActual<typeof import("../../api/templates")>("../../api/templates");
  return {
    ...actual,
    templatesApi: {
      ...actual.templatesApi,
      save: vi.fn(),
    },
  };
});
import { templatesApi } from "../../api/templates";

const saveMock = templatesApi.save as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => saveMock.mockReset());

function body(): TemplateBody {
  return {
    v: 1,
    application: "checkout-svc",
    testPlanBlobId: "plan-1",
    dataFilesBlobId: "data-1",
    fleetAllocation: [
      // worker 0 carries a per-worker override; worker 1 has none.
      { region: "us-east-1", count: 2, perNodeProperties: [{ threads: "10" }, {}] },
    ],
    saveResults: true,
  };
}

describe("SaveTemplateDialog", () => {
  it("surfaces per-worker parameters + data files + save-results in the summary", () => {
    render(<SaveTemplateDialog body={body()} onSaved={vi.fn()} onClose={vi.fn()} />);
    expect(screen.getByText(/Data files:/)).toBeInTheDocument();
    expect(screen.getByText(/Per-worker parameters:/)).toBeInTheDocument();
    expect(screen.getByText(/Save results:/)).toBeInTheDocument();
  });

  it("saves the full body — per-worker params + data files + saveResults all captured", async () => {
    saveMock.mockResolvedValue("tpl-blob-1");
    const onSaved = vi.fn();

    render(<SaveTemplateDialog body={body()} onSaved={onSaved} onClose={vi.fn()} />);
    fireEvent.change(screen.getByLabelText(/Template name/i), { target: { value: "my-tpl" } });
    fireEvent.click(screen.getByRole("button", { name: /Save template/i }));

    await waitFor(() => expect(saveMock).toHaveBeenCalled());
    const savedBody = saveMock.mock.calls[0][0] as TemplateBody;
    expect(savedBody.saveResults).toBe(true);
    expect(savedBody.dataFilesBlobId).toBe("data-1");
    expect(savedBody.fleetAllocation[0].perNodeProperties?.[0]).toEqual({ threads: "10" });
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith("tpl-blob-1"));
  });
});
