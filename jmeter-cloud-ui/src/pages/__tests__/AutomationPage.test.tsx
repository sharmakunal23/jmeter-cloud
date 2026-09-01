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
  it("offers exactly three tabs and shows one panel at a time", async () => {
    renderPage();

    const strip = await screen.findByRole("tablist", { name: "Automation sections" });
    const tabs = within(strip).getAllByRole("tab");
    expect(tabs.map((t) => t.textContent)).toEqual([
      "Workflow automation", "Platform reports", "Platform infrastructure",
    ]);
    // One panel, not three stacked sections — that is the point of the change.
    expect(screen.getAllByRole("tabpanel")).toHaveLength(1);
    expect(screen.getByRole("heading", { name: /Workflow automation/, level: 2 })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /Platform reports/, level: 2 })).not.toBeInTheDocument();
  });

  it("each tab's section explains itself behind an ⓘ, in ONE sentence", async () => {
    renderPage();
    await screen.findByRole("tablist", { name: "Automation sections" });

    for (const [tabName, tipLabel] of [
      ["Workflow automation", "About Workflow automation"],
      ["Platform reports", "About Platform reports"],
      ["Platform infrastructure", "About Platform infrastructure"],
    ]) {
      fireEvent.click(screen.getByRole("tab", { name: tabName }));
      const tip = await screen.findByRole("button", { name: tipLabel });
      const text = document.getElementById(tip.getAttribute("aria-controls") ?? "")?.textContent ?? "";
      expect(text.trim(), tipLabel).not.toBe("");
      expect(text.trim().split(".").filter((x) => x.trim() !== ""), tipLabel).toHaveLength(1);
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
    await screen.findByRole("tablist", { name: "Automation sections" });

    // Each tab shows only its own kind — and the counts on the tabs say so
    // before you click.
    expect(await screen.findByRole("tab", { name: /Workflow automation.*1/ })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: /Platform reports.*1/ })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: /Platform infrastructure.*2/ })).toBeInTheDocument();

    expect(screen.getByText("nightly")).toBeInTheDocument();
    expect(screen.queryByText("evening")).not.toBeInTheDocument();
    expect(screen.queryByText("readiness")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: /Platform reports/ }));
    expect(await screen.findByText("readiness")).toBeInTheDocument();
    expect(screen.queryByText("nightly")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: /Platform infrastructure/ }));
    expect(await screen.findByText("evening")).toBeInTheDocument();
    expect(screen.getByText("morning")).toBeInTheDocument();
    expect(screen.queryByText("nightly")).not.toBeInTheDocument();
  });

  it("a workflow schedule shows its group by display name and links the workflow", async () => {
    cron.list.mockResolvedValue([
      job({ cronJobId: "1", name: "nightly", kind: "LAUNCH_WORKFLOW", groupId: "cps",
            workflowId: "wf-1", workflowName: "Nightly regression" }),
    ]);

    renderPage();
    const section = await screen.findByRole("tabpanel");

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
    const section = await screen.findByRole("tabpanel");

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
    const section = await screen.findByRole("tabpanel");

    expect(within(section).getByRole("link", { name: "View" }))
      .toHaveAttribute("href", "/workflows/executions/ex-9");
  });

  it("scale out and scale in are one section, distinguished by a Direction chip", async () => {
    cron.list.mockResolvedValue([
      job({ cronJobId: "3", name: "evening", kind: "SCALE_IN", groupId: "cps", region: "na-east" }),
      job({ cronJobId: "4", name: "morning", kind: "SCALE_OUT", groupId: "cps", region: "na-west" }),
    ]);

    renderPage();
    await screen.findByRole("tablist", { name: "Automation sections" });
    fireEvent.click(screen.getByRole("tab", { name: /Platform infrastructure/ }));
    const section = await screen.findByRole("tabpanel");

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
    const section = await screen.findByRole("tabpanel");

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
    await screen.findByRole("tablist", { name: "Automation sections" });

    expect(await screen.findByText(/No workflow schedules/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: /Platform reports/ }));
    expect(await screen.findByText(/No report schedules/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: /Platform infrastructure/ }));
    expect(await screen.findByText(/No scaling schedules/)).toBeInTheDocument();
  });

  it("the tab's add button leads its header row — the same left placement every tab uses", async () => {
    cron.list.mockResolvedValue([job({ cronJobId: "c1", name: "nightly", kind: "LAUNCH_WORKFLOW", groupId: "cps" })]);
    renderPage();

    const add = await screen.findByRole("button", { name: "+ Workflow schedule" });
    const row = add.closest(".pageHeader__actions");
    expect(row).not.toBeNull();
    // First control in the row: the ⓘ trails it, so the button sits at the
    // left edge exactly as "+ Add cluster" does on Capacity → Clusters.
    expect(row!.firstElementChild).toBe(add);
  });

  it("filters the visible schedules by name and says so when nothing matches", async () => {
    cron.list.mockResolvedValue([
      job({ cronJobId: "c1", name: "nightly regression", kind: "LAUNCH_WORKFLOW", groupId: "cps" }),
      job({ cronJobId: "c2", name: "weekly soak", kind: "LAUNCH_WORKFLOW", groupId: "cps" }),
    ]);
    renderPage();

    expect(await screen.findByText("nightly regression")).toBeInTheDocument();
    const filter = screen.getByRole("searchbox", { name: /Filter schedules by name/i });
    expect(screen.getByText("2 of 2 schedules")).toBeInTheDocument();

    // The filter shares the list's toolbar row rather than sitting above it,
    // so the gap to the table is the same on every list in the app.
    expect(filter.closest(".dataList__toolbar")).not.toBeNull();

    fireEvent.change(filter, { target: { value: "soak" } });
    expect(screen.queryByText("nightly regression")).not.toBeInTheDocument();
    expect(screen.getByText("weekly soak")).toBeInTheDocument();
    expect(screen.getByText("1 of 2 schedules")).toBeInTheDocument();

    fireEvent.change(filter, { target: { value: "zzz" } });
    // A filter that matches nothing is not the first-run empty state.
    expect(screen.getByText(/No schedules match/)).toBeInTheDocument();
    expect(screen.queryByText(/No workflow schedules/)).not.toBeInTheDocument();
  });

  it("asks the backend once for every schedule — the page does not fetch per group", async () => {
    renderPage();
    await screen.findByRole("tablist", { name: "Automation sections" });

    expect(cron.list).toHaveBeenCalledTimes(1);
    expect(cron.list).toHaveBeenCalledWith(undefined, expect.anything());
  });
});
