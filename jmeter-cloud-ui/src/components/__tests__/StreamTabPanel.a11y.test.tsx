import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/react";
import { axe } from "vitest-axe";

import { StreamTabPanel } from "../StreamTabPanel";
import type { MemberState, RunFleetMember } from "../../api/runs";

// The LogTailPanel has its own a11y story; the sweep targets the shell (worker selector, counts).
vi.mock("../LogTailPanel", () => ({
  LogTailPanel: (props: { workerId: string; streamSource: string }) => (
    <div data-testid="logTailMock" aria-label={`mock log tail for ${props.workerId}`}>mocked</div>
  ),
}));

function makeMember(overrides: Partial<RunFleetMember> & { workerId: string; state: MemberState }): RunFleetMember {
  return { runId: "run-a11y", region: "local-east-1", fanoutStatusCode: null, podBaseUrl: "http://pod:8080",
    createdAt: "2026-05-10T12:00:00Z", ...overrides };
}
const TWO_LIVE = [makeMember({ workerId: "worker-a", state: "RUNNING" }), makeMember({ workerId: "worker-b", state: "RUNNING" })];
const HINT = "No fleet members yet — the run hasn't been fanned out to any workers.";

beforeEach(() => { window.localStorage.clear(); });
afterEach(() => { vi.clearAllMocks(); });

describe("StreamTabPanel — accessibility (vitest-axe)", () => {
  it.each([["console", "console"], ["logs", "jmeter"]] as const)("%s with workers has no axe violations — the worker selector is labelled", async (panelKey, source) => {
    const { container } = render(
      <StreamTabPanel runId="run-a11y" panelKey={panelKey} streamSource={source} fleetMembers={TWO_LIVE} runTerminal={false} emptyHint={HINT} />,
    );
    expect(await axe(container, { iframes: false })).toHaveNoViolations();
  });

  it("empty-fleet hint has no axe violations", async () => {
    const { container } = render(
      <StreamTabPanel runId="run-a11y" panelKey="console" streamSource="console" fleetMembers={[]} runTerminal={false} emptyHint={HINT} />,
    );
    expect(await axe(container, { iframes: false })).toHaveNoViolations();
  });
});
