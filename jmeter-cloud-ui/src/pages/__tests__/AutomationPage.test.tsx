import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor, within, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { AutomationPage } from "../AutomationPage";
import type { CronJobSummary } from "../../api/automation";
import type { ApplicationGroup } from "../../api/applicationGroups";

vi.mock("../../api/automation", async () => {
  const actual = await vi.importActual<typeof import("../../api/automation")>("../../api/automation");
  return {
    ...actual,
    cronJobsApi: {
      list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn(),
      enable: vi.fn(), disable: vi.fn(), fireNow: vi.fn(), skipNext: vi.fn(), history: vi.fn(),
    },
  };
});
vi.mock("../../api/applicationGroups", async () => {
  const actual = await vi.importActual<typeof import("../../api/applicationGroups")>("../../api/applicationGroups");
  return {
    ...actual,
    applicationGroupsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
  };
});
vi.mock("../../api/workflows", async () => {
  const actual = await vi.importActual<typeof import("../../api/workflows")>("../../api/workflows");
  return { ...actual, workflowsApi: { ...actual.workflowsApi, list: vi.fn(), groups: vi.fn() } };
});

import { cronJobsApi } from "../../api/automation";
import { applicationGroupsApi } from "../../api/applicationGroups";
import { workflowsApi } from "../../api/workflows";

const cron = cronJobsApi as unknown as Record<string, ReturnType<typeof vi.fn>>;
const groupsApi = applicationGroupsApi as unknown as { list: ReturnType<typeof vi.fn> };
const wf = workflowsApi as unknown as { list: ReturnType<typeof vi.fn> };

function group(groupId: string, name: string, regions: string[] = ["na-east"]): ApplicationGroup {
  return {
    groupId, name, description: null,
    capacity: regions.map((region) => ({ region, maxAvailable: 2 })),
    createdAt: "2026-08-31T00:00:00Z", applicationCount: 1,
  };
}

function job(partial: Partial<CronJobSummary> & Pick<CronJobSummary, "cronJobId" | "name" | "kind">): CronJobSummary {
  return {
    cronExpression: "0 2 * * *", timeZone: "UTC", enabled: true,
    createdAt: "2026-08-31T00:00:00Z", nextFireAt: "2026-09-01T02:00:00Z",
    ...partial,
  };
}

beforeEach(() => {
  for (const fn of Object.values(cron)) fn.mockReset();
  groupsApi.list.mockReset();
  wf.list.mockReset();
  groupsApi.list.mockResolvedValue([group("cps", "Servicing MQ"), group("demo", "Demo")]);
  wf.list.mockResolvedValue([]);
  cron.list.mockResolvedValue([]);
});

function renderPage() {
  return render(<MemoryRouter><AutomationPage /></MemoryRouter>);
}

describe("AutomationPage — three sections, group-scoped", () => {
  it("renders exactly the three sections, each with a one-sentence ⓘ", async () => {
    renderPage();

    expect(await screen.findByRole("heading", { name: /Workflow automation/, level: 2 })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Platform reports/, level: 2 })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Platform infrastructure/, level: 2 })).toBeInTheDocument();
    expect(screen.getAllByRole("heading", { level: 2 })).toHaveLength(3);

    // Every section explains itself behind an ⓘ, in ONE sentence — the house
    // rule for this control; anything longer belongs in the section body.
    for (const label of ["About Workflow automation", "About Platform reports", "About Platform infrastructure"]) {
      const tip = screen.getByRole("button", { name: label });
      const text = document.getElementById(tip.getAttribute("aria-controls") ?? "")?.textContent ?? "";
      expect(text.trim(), label).not.toBe("");
      expect(text.trim().split(".").filter((s) => s.trim() !== ""), label).toHaveLength(1);
    }
  });

  it("files each schedule under the section its kind belongs to", async () => {
    cron.list.mockResolvedValue([
      job({ cronJobId: "1", name: "nightly", kind: "LAUNCH_WORKFLOW", groupId: "cps",
            workflowId: "wf-1", workflowName: "Nightly regression" }),
      job({ cronJobId: "2", name: "readiness", kind: "INFRA_READINESS", recipients: "ops@x.com" }),
      job({ cronJobId: "3", name: "evening", kind: "SCALE_IN", groupId: "cps", region: "na-east" }),
      job({ cronJobId: "4", name: "morning", kind: "SCALE_OUT", groupId: "demo", region: "na-east" }),
    ]);

    renderPage();
    await screen.findByRole("heading", { name: /Workflow automation/, level: 2 });

    const workflowSection = screen.getByRole("region", { name: "Workflow automation" });
    expect(within(workflowSection).getByText("nightly")).toBeInTheDocument();
    expect(within(workflowSection).queryByText("evening")).not.toBeInTheDocument();
    expect(within(workflowSection).queryByText("readiness")).not.toBeInTheDocument();

    const reportSection = screen.getByRole("region", { name: "Platform reports" });
    expect(within(reportSection).getByText("readiness")).toBeInTheDocument();
    expect(within(reportSection).queryByText("nightly")).not.toBeInTheDocument();

    const infraSection = screen.getByRole("region", { name: "Platform infrastructure" });
    expect(within(infraSection).getByText("evening")).toBeInTheDocument();
    expect(within(infraSection).getByText("morning")).toBeInTheDocument();
    expect(within(infraSection).queryByText("nightly")).not.toBeInTheDocument();
  });

  it("a workflow schedule shows its group by display name and links the workflow", async () => {
    cron.list.mockResolvedValue([
      job({ cronJobId: "1", name: "nightly", kind: "LAUNCH_WORKFLOW", groupId: "cps",
            workflowId: "wf-1", workflowName: "Nightly regression" }),
    ]);

    renderPage();
    const section = await screen.findByRole("region", { name: "Workflow automation" });

    // The group's NAME, not its id — the id is an implementation detail.
    expect(within(section).getByText("Servicing MQ")).toBeInTheDocument();
    const link = within(section).getByRole("link", { name: "Nightly regression" });
    expect(link).toHaveAttribute("href", "/workflows/wf-1");
  });

  it("a schedule whose workflow was deleted says so instead of rendering a dead link", async () => {
    cron.list.mockResolvedValue([
      job({ cronJobId: "1", name: "orphan", kind: "LAUNCH_WORKFLOW", groupId: "cps", workflowId: null }),
    ]);

    renderPage();
    const section = await screen.findByRole("region", { name: "Workflow automation" });

    expect(within(section).getByText("deleted")).toBeInTheDocument();
    expect(within(section).queryByRole("link", { name: /workflow/i })).not.toBeInTheDocument();
  });

  it("the last fire links its execution, not a run", async () => {
    cron.list.mockResolvedValue([
      job({ cronJobId: "1", name: "nightly", kind: "LAUNCH_WORKFLOW", groupId: "cps",
            workflowId: "wf-1", workflowName: "WF", lastFiredExecutionId: "ex-9",
            lastFiredAt: "2026-08-31T02:00:00Z", lastFireStatus: "LAUNCHED" }),
    ]);

    renderPage();
    const section = await screen.findByRole("region", { name: "Workflow automation" });

    expect(within(section).getByRole("link", { name: "View" }))
      .toHaveAttribute("href", "/workflows/executions/ex-9");
  });

  it("scale out and scale in are one section, distinguished by a Direction chip", async () => {
    cron.list.mockResolvedValue([
      job({ cronJobId: "3", name: "evening", kind: "SCALE_IN", groupId: "cps", region: "na-east" }),
      job({ cronJobId: "4", name: "morning", kind: "SCALE_OUT", groupId: "cps", region: "na-west" }),
    ]);

    renderPage();
    const section = await screen.findByRole("region", { name: "Platform infrastructure" });

    expect(within(section).getByText("Scale in")).toBeInTheDocument();
    expect(within(section).getByText("Scale out")).toBeInTheDocument();
    expect(within(section).getByText("na-east")).toBeInTheDocument();
    expect(within(section).getByText("na-west")).toBeInTheDocument();
  });

  it("firing a schedule confirms first, and a SKIPPED outcome is surfaced with its reason", async () => {
    cron.list.mockResolvedValue([
      job({ cronJobId: "1", name: "nightly", kind: "LAUNCH_WORKFLOW", groupId: "cps",
            workflowId: "wf-1", workflowName: "WF" }),
    ]);
    cron.fireNow.mockResolvedValue({ outcome: "SKIPPED", error: "workflow is already running" });

    renderPage();
    const section = await screen.findByRole("region", { name: "Workflow automation" });

    fireEvent.click(within(section).getByRole("button", { name: "Run now" }));
    // Nothing has fired yet — the confirm is the only place the operator sees
    // what firing early actually does.
    expect(cron.fireNow).not.toHaveBeenCalled();

    const dialog = screen.getByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: /^Run now$/ }));

    await waitFor(() => expect(cron.fireNow).toHaveBeenCalledWith("1"));
    expect(await screen.findByText(/already running/)).toBeInTheDocument();
  });

  it("the empty states point at what to do, per section", async () => {
    renderPage();
    await screen.findByRole("heading", { name: /Workflow automation/, level: 2 });

    expect(screen.getByText(/No workflow schedules/)).toBeInTheDocument();
    expect(screen.getByText(/No report schedules/)).toBeInTheDocument();
    expect(screen.getByText(/No scaling schedules/)).toBeInTheDocument();
  });

  it("asks the backend once for every schedule — the page does not fetch per group", async () => {
    renderPage();
    await screen.findByRole("heading", { name: /Workflow automation/, level: 2 });

    expect(cron.list).toHaveBeenCalledTimes(1);
    expect(cron.list).toHaveBeenCalledWith(undefined, expect.anything());
  });
});
