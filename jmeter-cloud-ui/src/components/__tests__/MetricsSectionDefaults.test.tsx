import { beforeEach, describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";

import { useSectionOpen, type MetricsSectionId } from "../metrics/MetricsSection";

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
