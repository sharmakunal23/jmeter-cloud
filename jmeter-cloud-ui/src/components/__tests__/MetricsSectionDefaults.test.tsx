import { beforeEach, describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";

import { MetricsSection, useSectionOpen, type MetricsSectionId } from "../metrics/MetricsSection";

function Probe({ id }: { id: MetricsSectionId }) {
  const [open] = useSectionOpen(id);
  return <span data-testid={id}>{String(open)}</span>;
}

/**
 * Which sections of the workflow execution's Metrics board start open. The
 * numbers and the throughput picture are what the tab is opened for; the error
 * charts are where an operator goes once something already looks wrong.
 */
describe("workflow metrics sections — defaults", () => {
  beforeEach(() => window.localStorage.clear());

  it("opens key metrics, the summary and throughput, and leaves errors closed", () => {
    render(
      <>
        <Probe id="wfKeyMetrics" />
        <Probe id="wfSummary" />
        <Probe id="wfThroughput" />
        <Probe id="wfErrors" />
      </>,
    );
    expect(screen.getByTestId("wfKeyMetrics")).toHaveTextContent("true");
    expect(screen.getByTestId("wfSummary")).toHaveTextContent("true");
    expect(screen.getByTestId("wfThroughput")).toHaveTextContent("true");
    expect(screen.getByTestId("wfErrors")).toHaveTextContent("false");
  });

  it("has its own ids, so collapsing one board does not collapse the run's", () => {
    window.localStorage.setItem(
      "jmeterCloud.metrics.sections",
      JSON.stringify({ wfThroughput: false }),
    );
    render(<><Probe id="wfThroughput" /><Probe id="throughput" /></>);
    expect(screen.getByTestId("wfThroughput")).toHaveTextContent("false");
    expect(screen.getByTestId("throughput")).toHaveTextContent("true");
  });
});

describe("MetricsSection — header controls", () => {
  it("a section's controls are reachable only while it is open", () => {
    // The response-time percentile picker lives in the header rather than above
    // its chart (which made that column taller than its neighbour), so it has
    // to disappear with the charts it steers.
    const { rerender } = render(
      <MetricsSection
        id="wfThroughput" title="Throughput and response time" open onToggle={() => {}}
        controls={<button type="button">P95</button>}
      >
        <p>charts</p>
      </MetricsSection>,
    );
    expect(screen.getByRole("button", { name: "P95" })).toBeInTheDocument();
    expect(screen.getByText("charts")).toBeInTheDocument();

    rerender(
      <MetricsSection
        id="wfThroughput" title="Throughput and response time" open={false} onToggle={() => {}}
        controls={<button type="button">P95</button>}
      >
        <p>charts</p>
      </MetricsSection>,
    );
    expect(screen.queryByRole("button", { name: "P95" })).toBeNull();
    expect(screen.queryByText("charts")).toBeNull();
  });
});
