import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { CreateReportScheduleDialog } from "../CreateReportScheduleDialog";

vi.mock("../../api/automation", async () => {
  const actual = await vi.importActual<typeof import("../../api/automation")>("../../api/automation");
  return {
    ...actual,
    cronJobsApi: { create: vi.fn() },
    automationReportsApi: { preview: vi.fn() },
  };
});

import { automationReportsApi, cronJobsApi } from "../../api/automation";
const cronJobs = cronJobsApi as unknown as { create: ReturnType<typeof vi.fn> };
const reports = automationReportsApi as unknown as { preview: ReturnType<typeof vi.fn> };

beforeEach(() => { cronJobs.create.mockReset(); reports.preview.mockReset(); });

describe("CreateReportScheduleDialog", () => {
  it("creates an INFRA_READINESS schedule with recipients (no application)", async () => {
    cronJobs.create.mockResolvedValue({ cronJobId: "01JOB", name: "daily-infra" });
    const onCreated = vi.fn();
    render(<CreateReportScheduleDialog onCreated={onCreated} onClose={vi.fn()} />);

    fireEvent.change(screen.getByLabelText(/^Name/i), { target: { value: "daily-infra" } });
    fireEvent.change(screen.getByLabelText(/Recipients/i), { target: { value: "ops@example.com" } });
    fireEvent.click(screen.getByRole("button", { name: /Create schedule/i }));

    await waitFor(() => expect(cronJobs.create).toHaveBeenCalledWith(
      expect.objectContaining({ name: "daily-infra", kind: "INFRA_READINESS", recipients: "ops@example.com" }),
    ));
    // No per-app fields are sent.
    const body = cronJobs.create.mock.calls[0][0];
    expect(body).not.toHaveProperty("templateBlobId");
    expect(body).not.toHaveProperty("region");
    await waitFor(() => expect(onCreated).toHaveBeenCalled());
  });

  it("blank recipients are allowed (server uses the env fallback)", async () => {
    cronJobs.create.mockResolvedValue({ cronJobId: "01JOB", name: "infra" });
    render(<CreateReportScheduleDialog onCreated={vi.fn()} onClose={vi.fn()} />);

    fireEvent.change(screen.getByLabelText(/^Name/i), { target: { value: "infra" } });
    fireEvent.click(screen.getByRole("button", { name: /Create schedule/i }));

    await waitFor(() => expect(cronJobs.create).toHaveBeenCalled());
    expect(cronJobs.create.mock.calls[0][0].recipients).toBeUndefined();
  });

  it("creates a DAILY_REPORT schedule when the kind picker is switched", async () => {
    cronJobs.create.mockResolvedValue({ cronJobId: "01JOB", name: "daily-perf" });
    const onCreated = vi.fn();
    render(<CreateReportScheduleDialog onCreated={onCreated} onClose={vi.fn()} />);

    fireEvent.change(screen.getByLabelText(/^Report/i), { target: { value: "DAILY_REPORT" } });
    fireEvent.change(screen.getByLabelText(/^Name/i), { target: { value: "daily-perf" } });
    fireEvent.click(screen.getByRole("button", { name: /Create schedule/i }));

    await waitFor(() => expect(cronJobs.create).toHaveBeenCalledWith(
      expect.objectContaining({ name: "daily-perf", kind: "DAILY_REPORT" }),
    ));
    await waitFor(() => expect(onCreated).toHaveBeenCalled());
  });

  it("sends the custom subject + intro on create", async () => {
    cronJobs.create.mockResolvedValue({ cronJobId: "01JOB", name: "infra" });
    render(<CreateReportScheduleDialog onCreated={vi.fn()} onClose={vi.fn()} />);

    fireEvent.change(screen.getByLabelText(/^Name/i), { target: { value: "infra" } });
    fireEvent.change(screen.getByLabelText(/Subject/i), { target: { value: "Custom subj" } });
    fireEvent.change(screen.getByLabelText(/Intro note/i), { target: { value: "Hello team" } });
    fireEvent.click(screen.getByRole("button", { name: /Create schedule/i }));

    await waitFor(() => expect(cronJobs.create).toHaveBeenCalledWith(
      expect.objectContaining({ customSubject: "Custom subj", customIntro: "Hello team" }),
    ));
  });

  it("Preview email opens the preview modal with the typed subject/intro", async () => {
    reports.preview.mockResolvedValue({ subject: "Custom subj", html: "<p>Hello team</p>" });
    render(<CreateReportScheduleDialog onCreated={vi.fn()} onClose={vi.fn()} />);

    fireEvent.change(screen.getByLabelText(/Subject/i), { target: { value: "Custom subj" } });
    fireEvent.change(screen.getByLabelText(/Intro note/i), { target: { value: "Hello team" } });
    fireEvent.click(screen.getByRole("button", { name: /Preview email/i }));

    await waitFor(() => expect(reports.preview).toHaveBeenCalledWith(
      "INFRA_READINESS",
      expect.objectContaining({ customSubject: "Custom subj", customIntro: "Hello team" }),
      expect.anything(),
    ));
    expect(await screen.findByText("Custom subj")).toBeInTheDocument();
  });
});
