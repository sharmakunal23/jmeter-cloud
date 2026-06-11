import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { CreateScheduleDialog } from "../CreateScheduleDialog";

vi.mock("../../api/automation", async () => {
  const actual = await vi.importActual<typeof import("../../api/automation")>("../../api/automation");
  return { ...actual, cronJobsApi: { create: vi.fn() } };
});
vi.mock("../../api/templates", async () => {
  const actual = await vi.importActual<typeof import("../../api/templates")>("../../api/templates");
  return { ...actual, templatesApi: { list: vi.fn() } };
});

import { cronJobsApi } from "../../api/automation";
import { templatesApi } from "../../api/templates";
const cronJobs  = cronJobsApi  as unknown as { create: ReturnType<typeof vi.fn> };
const templates = templatesApi as unknown as { list: ReturnType<typeof vi.fn> };

beforeEach(() => {
  cronJobs.create.mockReset();
  templates.list.mockReset();
});

function tpl(blobId: string, name: string, application = "checkout") {
  return { blobId, name, application, description: null, uploadedAt: "2026-05-12T00:00:00Z", sizeBytes: 10 };
}

function renderDialog(onCreated = vi.fn(), onClose = vi.fn(), regions: string[] = ["us-east-1", "us-west-2"]) {
  render(
    <MemoryRouter>
      <CreateScheduleDialog application="checkout" regions={regions} onCreated={onCreated} onClose={onClose} />
    </MemoryRouter>,
  );
  return { onCreated, onClose };
}

describe("CreateScheduleDialog", () => {
  it("loads templates for the application and submits a create", async () => {
    templates.list.mockResolvedValue([tpl("01TPL", "nightly-baseline"), tpl("01TPL2", "other-app", "search")]);
    cronJobs.create.mockResolvedValue({ cronJobId: "01JOB", name: "nightly" });
    const { onCreated } = renderDialog();

    // Only checkout's template is offered (search's is filtered out).
    const select = await screen.findByLabelText(/Template/i) as HTMLSelectElement;
    expect(screen.getByRole("option", { name: "nightly-baseline" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "other-app" })).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^Name/i), { target: { value: "nightly" } });
    fireEvent.change(select, { target: { value: "01TPL" } });
    fireEvent.click(screen.getByRole("button", { name: /Create schedule/i }));

    await waitFor(() => expect(cronJobs.create).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "nightly", applicationName: "checkout", templateBlobId: "01TPL",
        // Default Simple-mode preset = daily at 02:00; timeZone defaults to the
        // browser zone (not pinned here — the builder owns it).
        cronExpression: "0 2 * * *",
      }),
    ));
    expect(typeof cronJobs.create.mock.calls[0][0].timeZone).toBe("string");
    await waitFor(() => expect(onCreated).toHaveBeenCalled());
  });

  it("DRAIN_REGION: shows a region picker (not a template) and submits kind+region", async () => {
    templates.list.mockResolvedValue([]);
    cronJobs.create.mockResolvedValue({ cronJobId: "01JOB", name: "overnight-drain" });
    renderDialog();

    fireEvent.change(screen.getByLabelText(/^Name/i), { target: { value: "overnight-drain" } });
    fireEvent.change(screen.getByLabelText(/Action/i), { target: { value: "DRAIN_REGION" } });

    const regionSelect = await screen.findByLabelText(/^Region/i);
    fireEvent.change(regionSelect, { target: { value: "us-west-2" } });
    expect(screen.queryByLabelText(/Template/i)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Create schedule/i }));
    await waitFor(() => expect(cronJobs.create).toHaveBeenCalledWith(
      expect.objectContaining({ name: "overnight-drain", kind: "DRAIN_REGION", region: "us-west-2" }),
    ));
    // No template should be sent for a drain job.
    expect(cronJobs.create.mock.calls[0][0]).not.toHaveProperty("templateBlobId");
  });

  it("surfaces a conflict error inline", async () => {
    const { CronJobApiError } = await vi.importActual<typeof import("../../api/automation")>("../../api/automation");
    templates.list.mockResolvedValue([tpl("01TPL", "nightly-baseline")]);
    cronJobs.create.mockRejectedValue(new CronJobApiError(409, "CRON_JOB_CONFLICT", "dup"));
    renderDialog();

    fireEvent.change(screen.getByLabelText(/^Name/i), { target: { value: "nightly" } });
    fireEvent.change(await screen.findByLabelText(/Template/i), { target: { value: "01TPL" } });
    fireEvent.click(screen.getByRole("button", { name: /Create schedule/i }));

    expect(await screen.findByText(/already exists/i)).toBeInTheDocument();
  });
});
