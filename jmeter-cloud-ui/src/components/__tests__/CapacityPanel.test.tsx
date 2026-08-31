import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { CapacityPanel } from "../workflow/CapacityPanel";
import type { WorkflowValidation } from "../../api/workflows";

function renderPanel(validation: WorkflowValidation | null) {
  return render(
    <MemoryRouter>
      <CapacityPanel validation={validation} groupId="cps" />
    </MemoryRouter>,
  );
}

const ok: WorkflowValidation = {
  valid: true, errors: [], warnings: [],
  capacity: [{ region: "na-east", peakWorkers: 4, tasks: ["Test A", "Test B"], reserved: 8, fits: true }],
};

const over: WorkflowValidation = {
  valid: true, errors: [], warnings: ["over"],
  capacity: [{ region: "na-east", peakWorkers: 12, tasks: ["Test A", "Test B", "Test C"], reserved: 8, fits: false }],
};

describe("CapacityPanel", () => {
  it("shows the peak against the reservation", () => {
    renderPanel(ok);
    expect(screen.getByText("na-east")).toBeInTheDocument();
    expect(screen.getByText("4")).toBeInTheDocument();
    expect(screen.getByText("8")).toBeInTheDocument();
    expect(screen.getByText(/Fits the group's reservation/i)).toBeInTheDocument();
  });

  it("names the tasks behind the peak, so a surprising number explains itself", () => {
    renderPanel(over);
    expect(screen.getByTitle("at once: Test A + Test B + Test C")).toBeInTheDocument();
  });

  it("says what an over-subscribed graph will do, not just that it is over", () => {
    renderPanel(over);
    expect(screen.getByText(/refused at launch/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Reserve more/i })).toHaveAttribute("href", "/capacity/groups/cps");
  });

  // A card that only ever said "no load tests, so no workers" was permanent
  // furniture in the column the task settings need; the status bar above the
  // canvas carries the group's reservation instead.
  it("renders nothing at all when there is no load test to weigh", () => {
    const { container } = renderPanel({ valid: true, errors: [], warnings: [], capacity: [] });
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing while the check is still in flight, rather than a placeholder card", () => {
    const { container } = renderPanel(null);
    expect(container).toBeEmptyDOMElement();
  });
});
