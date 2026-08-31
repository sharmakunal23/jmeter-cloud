import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";

import { ExecutionStateChip, labelFor, toneFor } from "../workflow/ExecutionStateChip";

describe("ExecutionStateChip", () => {
  it("calls a skipped task 'Not executed' — nothing ran, and 'Skipped' read as a choice", () => {
    expect(labelFor("SKIPPED")).toBe("Not executed");
    render(<ExecutionStateChip state="SKIPPED" />);
    expect(screen.getByText("Not executed")).toBeInTheDocument();
  });

  it("never dresses a non-success as success", () => {
    // The regression this guards: a run whose task failed showed a green
    // SUCCEEDED chip because the graph had an on-failure branch.
    for (const state of ["FAILED", "CANCELLED", "SKIPPED"] as const) {
      expect(toneFor(state)).not.toBe("chip--ok");
      expect(labelFor(state)).not.toMatch(/succe/i);
    }
    expect(toneFor("SUCCEEDED")).toBe("chip--ok");
    expect(toneFor("FAILED")).toBe("chip--err");
  });

  it("falls back to the raw state rather than rendering nothing", () => {
    expect(labelFor("WAT" as never)).toBe("WAT");
    expect(toneFor("WAT" as never)).toBe("chip--muted");
  });
});
