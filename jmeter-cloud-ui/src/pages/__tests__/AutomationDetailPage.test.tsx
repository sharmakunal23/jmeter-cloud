import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { AutomationDetailPage } from "../AutomationDetailPage";
import type { Application } from "../../api/applications";
import type { CronJobSummary } from "../../api/automation";

vi.mock("../../api/applications", async () => {
  const actual = await vi.importActual<typeof import("../../api/applications")>("../../api/applications");
  return {
    ...actual,
    applicationsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
  };
});
vi.mock("../../api/automation", async () => {
  const actual = await vi.importActual<typeof import("../../api/automation")>("../../api/automation");
  return {
    ...actual,
    cronJobsApi: {
      list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(),
      delete: vi.fn(), enable: vi.fn(), disable: vi.fn(), fireNow: vi.fn(),
      skipNext: vi.fn(), history: vi.fn(),
    },
  };
});
vi.mock("../../api/applicationGroups", async () => {
  const actual = await vi.importActual<typeof import("../../api/applicationGroups")>("../../api/applicationGroups");
  return {
    ...actual,
    applicationGroupsApi: {
      ...actual.applicationGroupsApi,
      get: vi.fn().mockResolvedValue({ groupId: "cps", name: "Servicing MQ", createdAt: "2026-05-12T00:00:00Z",
        capacity: [{ region: "us-east", maxAvailable: 1 }] }),
    },
  };
});
vi.mock("../../api/templates", async () => {
  const actual = await vi.importActual<typeof import("../../api/templates")>("../../api/templates");
  return { ...actual, templatesApi: { list: vi.fn(), save: vi.fn(), load: vi.fn(), delete: vi.fn() } };
});

import { applicationsApi } from "../../api/applications";
import { cronJobsApi } from "../../api/automation";
import { templatesApi } from "../../api/templates";
const apps     = applicationsApi as unknown as { list: ReturnType<typeof vi.fn> };
const cronJobs = cronJobsApi    as unknown as Record<string, ReturnType<typeof vi.fn>>;
const templates = templatesApi  as unknown as { list: ReturnType<typeof vi.fn> };

beforeEach(() => {
  apps.list.mockReset();
  Object.values(cronJobs).forEach((m) => m.mockReset());
  templates.list.mockReset();
  templates.list.mockResolvedValue([]);
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
function job(over: Partial<CronJobSummary> = {}): CronJobSummary {
  return {
    cronJobId: "01JOB",
    name: "nightly",
    applicationName: "checkout",
    templateBlobId: "01TPLZZZZZZZZZZZZZZZZZZZZ",
    cronExpression: "0 2 * * *",
    timeZone: "UTC",
    enabled: true,
    createdAt: "2026-05-12T00:00:00Z",
    lastFiredAt: null,
    lastFiredRunId: null,
    lastFireStatus: null,
    nextFireAt: null,
    kind: "LAUNCH_RUN",
    region: null,
    ...over,
  };
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/automation"          element={<div>automation-list-stub</div>} />
        <Route path="/automation/:appName" element={<AutomationDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("AutomationDetailPage", () => {
  it("renders header nav links + an enabled '+ New schedule' button", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cronJobs.list.mockResolvedValue([]);

    renderAt("/automation/checkout");

    await screen.findByRole("link", { name: /Open Application/ });
    expect(screen.getByRole("link", { name: /Open Application/ })).toHaveAttribute(
      "href", "/applications/checkout",
    );
    // "Launch a Run →" was intentionally removed — only Open Application + New schedule remain.
    expect(screen.queryByRole("link", { name: /Launch a Run/ })).not.toBeInTheDocument();
    const newBtn = screen.getByRole("button", { name: /\+ New schedule/ });
    expect(newBtn).toBeEnabled();
  });

  it("Empty state guides the operator at the saved Templates for the same app", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cronJobs.list.mockResolvedValue([]);

    renderAt("/automation/checkout");

    await waitFor(() => {
      expect(screen.getByText(/No schedules for/i)).toBeInTheDocument();
    });
    const tplLink = screen.getByRole("link", { name: "template" });
    expect(tplLink).toHaveAttribute("href", "/templates/checkout");
  });

  it("opens the CreateScheduleDialog when '+ New schedule' is clicked", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cronJobs.list.mockResolvedValue([]);

    renderAt("/automation/checkout");

    await screen.findByRole("button", { name: /\+ New schedule/ });
    fireEvent.click(screen.getByRole("button", { name: /\+ New schedule/ }));

    expect(await screen.findByRole("dialog", { name: /New schedule/i })).toBeInTheDocument();
    await waitFor(() => expect(templates.list).toHaveBeenCalled());
  });

  it("scopes the cron-job list to the current app (filters out others)", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cronJobs.list.mockResolvedValue([
      job({ cronJobId: "C1", name: "checkout-nightly", applicationName: "checkout" }),
      job({ cronJobId: "S1", name: "search-nightly",   applicationName: "search" }),
    ]);

    renderAt("/automation/checkout");

    expect(await screen.findByText("checkout-nightly")).toBeInTheDocument();
    expect(screen.queryByText("search-nightly")).not.toBeInTheDocument();
  });

  it("Fire now confirms in a modal, then calls the API and surfaces the outcome", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cronJobs.list.mockResolvedValue([job({ cronJobId: "C1", name: "checkout-nightly" })]);
    cronJobs.fireNow.mockResolvedValue({ outcome: "LAUNCHED", runId: "01RUNZZZZZZZZZZZZZZZZZZZZZZ", error: null });

    renderAt("/automation/checkout");

    await screen.findByText("checkout-nightly");
    // Row button opens the confirm dialog — it does NOT fire immediately.
    fireEvent.click(screen.getByRole("button", { name: /Fire now/ }));
    expect(cronJobs.fireNow).not.toHaveBeenCalled();

    const dialog = await screen.findByRole("dialog", { name: /Fire "checkout-nightly" now\?/i });
    fireEvent.click(within(dialog).getByRole("button", { name: /Fire now/ }));

    await waitFor(() => expect(cronJobs.fireNow).toHaveBeenCalledWith("C1"));
    expect(await screen.findByText(/run 01RUNZZZZZZZZZZZZZZZZZZZZZZ launched/i)).toBeInTheDocument();
  });

  it("the enabled/disabled toggle disables an enabled schedule (no confirm)", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cronJobs.list.mockResolvedValue([job({ cronJobId: "C1", name: "checkout-nightly", enabled: true })]);
    cronJobs.disable.mockResolvedValue(job({ cronJobId: "C1", enabled: false }));

    renderAt("/automation/checkout");

    await screen.findByText("checkout-nightly");
    // The color-coded pill shows the current state ("Enabled") and toggles on click.
    fireEvent.click(screen.getByRole("button", { name: /^Enabled$/ }));

    await waitFor(() => expect(cronJobs.disable).toHaveBeenCalledWith("C1"));
  });

  it("surfaces action feedback as a floating toast, not a persistent inline banner", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cronJobs.list.mockResolvedValue([job({ cronJobId: "C1", name: "checkout-nightly", enabled: true })]);
    cronJobs.disable.mockResolvedValue(job({ cronJobId: "C1", enabled: false }));

    renderAt("/automation/checkout");

    await screen.findByText("checkout-nightly");
    fireEvent.click(screen.getByRole("button", { name: /^Enabled$/ }));

    const msg = await screen.findByText(/disabled\./i);
    // Rendered inside the floating .toast overlay (matches the Capacity page),
    // not the old persistent .emptyStateInline banner that lingered.
    expect(msg.closest(".toast")).not.toBeNull();
    expect(document.querySelector(".emptyStateInline")).toBeNull();
  });

  it("Skip next confirms then advances the schedule via skipNext", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    const future = new Date(Date.now() + 3600_000).toISOString();
    cronJobs.list.mockResolvedValue([job({ cronJobId: "C1", name: "checkout-nightly", enabled: true, nextFireAt: future })]);
    cronJobs.skipNext.mockResolvedValue(job({ cronJobId: "C1", enabled: true }));

    renderAt("/automation/checkout");

    await screen.findByText("checkout-nightly");
    fireEvent.click(screen.getByRole("button", { name: /Skip next/ }));
    const dialog = await screen.findByRole("dialog", { name: /Skip the next "checkout-nightly" run\?/i });
    fireEvent.click(within(dialog).getByRole("button", { name: /Skip next run/ }));

    await waitFor(() => expect(cronJobs.skipNext).toHaveBeenCalledWith("C1"));
  });

  it("Edit opens a prefilled dialog and saves via update (PUT)", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cronJobs.list.mockResolvedValue([job({ cronJobId: "C1", name: "checkout-nightly", enabled: true })]);
    cronJobs.update.mockResolvedValue(job({ cronJobId: "C1", name: "checkout-nightly" }));

    renderAt("/automation/checkout");

    await screen.findByText("checkout-nightly");
    fireEvent.click(screen.getByRole("button", { name: /^Edit$/ }));

    const dialog = await screen.findByRole("dialog", { name: /Edit schedule/i });
    // Name is prefilled from the existing schedule.
    expect(within(dialog).getByLabelText(/Name/)).toHaveValue("checkout-nightly");
    fireEvent.click(within(dialog).getByRole("button", { name: /Save changes/ }));

    await waitFor(() => expect(cronJobs.update).toHaveBeenCalledWith("C1", expect.objectContaining({ name: "checkout-nightly" })));
  });

  it("notFound state shows when the appName isn't a registered app", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cronJobs.list.mockResolvedValue([]);

    renderAt("/automation/does-not-exist");

    await screen.findByText(/not found/i);
  });
});
