import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { axe } from "vitest-axe";
import { useEffect } from "react";

import { RunStreamsPanel } from "../RunStreamsPanel";
import type { MemberState, RunFleetMember } from "../../api/runs";

// Mock LogTailPanel so the a11y sweep targets the surrounding shell
// (tab strip, worker selector, panel container) rather than getting
// noise from the LogTailPanel's own internals — that component has its
// own a11y story and isn't the subject of this test.
function LogTailMock(props: { workerId: string; streamSource: string }) {
  useEffect(() => {}, []);
  return (
    <div data-testid="logTailMock" aria-label={`mock log tail for ${props.workerId}`}>
      mocked LogTailPanel({props.workerId}/{props.streamSource})
    </div>
  );
}

vi.mock("../LogTailPanel", () => ({ LogTailPanel: LogTailMock }));

// HM-3 — keep the a11y sweep focused on the shell; the historical
// metrics panel has its own dedicated a11y assertions.
vi.mock("../MetricsTabPanel", () => ({
  MetricsTabPanel: ({ runId }: { runId: string }) => (
    <div data-testid="metricsTabMock">mocked MetricsTabPanel for {runId}</div>
  ),
}));

function makeMember(overrides: Partial<RunFleetMember> & { workerId: string; state: MemberState }): RunFleetMember {
  return {
    runId: "run-a11y",
    region: "local-east-1",
    fanoutStatusCode: null,
    podBaseUrl: "http://pod:8080",
    createdAt: "2026-05-10T12:00:00Z",
    ...overrides,
  };
}

const TWO_LIVE: RunFleetMember[] = [
  makeMember({ workerId: "worker-a", state: "RUNNING" }),
  makeMember({ workerId: "worker-b", state: "RUNNING" }),
];

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("RunStreamsPanel — accessibility (vitest-axe)", () => {
  it("default Metrics tab has no axe violations", async () => {
    const { container } = render(
      <RunStreamsPanel runId="run-a11y" fleetMembers={TWO_LIVE} runState="RUNNING" />,
    );
    // iframes:false → don't recurse into the Grafana iframe (jsdom can't
    // postMessage across frames; the iframe content isn't ours to a11y-test
    // here anyway).
    const results = await axe(container, { iframes: false });
    expect(results).toHaveNoViolations();
  });

  it("Console tab with workers has no axe violations — worker selector is properly labelled", async () => {
    const { container } = render(
      <RunStreamsPanel runId="run-a11y" fleetMembers={TWO_LIVE} runState="RUNNING" />,
    );
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    // iframes:false → don't recurse into the Grafana iframe (jsdom can't
    // postMessage across frames; the iframe content isn't ours to a11y-test
    // here anyway).
    const results = await axe(container, { iframes: false });
    expect(results).toHaveNoViolations();
  });

  it("Logs tab with workers has no axe violations — same shell with the jmeter source", async () => {
    const { container } = render(
      <RunStreamsPanel runId="run-a11y" fleetMembers={TWO_LIVE} runState="RUNNING" />,
    );
    fireEvent.click(screen.getByRole("tab", { name: "Logs" }));
    // iframes:false → don't recurse into the Grafana iframe (jsdom can't
    // postMessage across frames; the iframe content isn't ours to a11y-test
    // here anyway).
    const results = await axe(container, { iframes: false });
    expect(results).toHaveNoViolations();
  });

  it("empty-fleet hint has no axe violations", async () => {
    const { container } = render(
      <RunStreamsPanel runId="run-a11y" fleetMembers={[]} runState="PREPARING" />,
    );
    fireEvent.click(screen.getByRole("tab", { name: "Console" }));
    // iframes:false → don't recurse into the Grafana iframe (jsdom can't
    // postMessage across frames; the iframe content isn't ours to a11y-test
    // here anyway).
    const results = await axe(container, { iframes: false });
    expect(results).toHaveNoViolations();
  });

  it("ARIA structural contract — tablist/tab/tabpanel roles wired with cross-references", () => {
    render(<RunStreamsPanel runId="run-a11y" fleetMembers={TWO_LIVE} runState="RUNNING" />);

    const tablist = screen.getByRole("tablist", { name: "Run stream selector" });
    expect(tablist).toBeInTheDocument();

    const metrics = screen.getByRole("tab", { name: "Metrics" });
    const console_ = screen.getByRole("tab", { name: "Console" });
    const logs = screen.getByRole("tab", { name: "Logs" });

    // Every tab carries an id + aria-controls pointing at its panel id.
    expect(metrics.id).toBe("runStreamsTab-metrics");
    expect(metrics.getAttribute("aria-controls")).toBe("runStreamsPanel-metrics");
    expect(console_.id).toBe("runStreamsTab-console");
    expect(console_.getAttribute("aria-controls")).toBe("runStreamsPanel-console");
    expect(logs.id).toBe("runStreamsTab-logs");
    expect(logs.getAttribute("aria-controls")).toBe("runStreamsPanel-logs");

    // Roving tabindex: only the selected tab is in the tab order.
    expect(metrics).toHaveAttribute("tabindex", "0");
    expect(console_).toHaveAttribute("tabindex", "-1");
    expect(logs).toHaveAttribute("tabindex", "-1");

    // The active panel back-references the active tab.
    const panel = screen.getByRole("tabpanel");
    expect(panel.id).toBe("runStreamsPanel-metrics");
    expect(panel.getAttribute("aria-labelledby")).toBe("runStreamsTab-metrics");
  });
});
