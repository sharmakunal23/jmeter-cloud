import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { axe } from "vitest-axe";

import { RunSummaryChips, type SubmitChipState } from "../RunSummaryChips";
import type { FleetAllocationEntry } from "../../api/runs";

function setup(overrides: Partial<{
  application: string;
  planName: string | null;
  planSelected: boolean;
  allocation: FleetAllocationEntry[];
  submit: SubmitChipState;
}> = {}) {
  const defaults = {
    application: "",
    planName: null as string | null,
    planSelected: false,
    allocation: [] as FleetAllocationEntry[],
    submit: { status: "idle" as const } as SubmitChipState,
  };
  return render(<RunSummaryChips {...defaults} {...overrides} />);
}

/** Find a chip by its label text, return the value span next to it. */
function chipValue(label: string): string {
  const labelEl = screen.getByText(label, { exact: true });
  const chip = labelEl.closest(".runSummaryChip");
  if (!chip) throw new Error(`no chip wrapper found for label '${label}'`);
  const valueEl = chip.querySelector(".runSummaryChip__value");
  if (!valueEl) throw new Error(`no value span found in chip '${label}'`);
  return valueEl.textContent ?? "";
}

function chipVariantClass(label: string): string {
  const labelEl = screen.getByText(label, { exact: true });
  const chip = labelEl.closest(".runSummaryChip") as HTMLElement | null;
  if (!chip) throw new Error(`no chip wrapper for '${label}'`);
  return chip.className;
}

describe("RunSummaryChips — live values", () => {
  it("totals: empty allocation → 0 pods / 0 regions", () => {
    setup();
    expect(chipValue("Total workers")).toBe("0");
    expect(chipValue("Regions")).toBe("0");
  });

  it("totals: sums counts across allocation entries", () => {
    setup({
      allocation: [
        { region: "local-east-1", count: 3 },
        { region: "local-west-2", count: 2 },
      ],
    });
    expect(chipValue("Total workers")).toBe("5");
    expect(chipValue("Regions")).toBe("2");
  });

  it("regions: ignores entries with count=0 so empty rows don't inflate the count", () => {
    setup({
      allocation: [
        { region: "local-east-1", count: 1 },
        { region: "local-west-2", count: 0 },
        { region: "local-eu-1",   count: 0 },
      ],
    });
    expect(chipValue("Regions")).toBe("1");
  });

  it("plan: shows '—' when none chosen, the name when chosen", () => {
    const { rerender } = setup();
    expect(chipValue("Plan")).toBe("—");
    rerender(<RunSummaryChips
      application="checkout-svc"
      planName="checkout-load"
      planSelected
      allocation={[]}
      submit={{ status: "idle" }}
    />);
    expect(chipValue("Plan")).toBe("checkout-load");
  });

  it("plan: truncates names longer than 24 chars with an ellipsis + title attribute carries the full name", () => {
    const longName = "a-very-long-plan-name-that-exceeds-the-cap";
    setup({ planName: longName, planSelected: true });

    const value = chipValue("Plan");
    expect(value.length).toBeLessThanOrEqual(24);
    expect(value.endsWith("…")).toBe(true);

    const labelEl = screen.getByText("Plan");
    const chip = labelEl.closest(".runSummaryChip");
    expect(chip).toHaveAttribute("title", longName);
  });
});

describe("RunSummaryChips — Status state machine", () => {
  it("Submitting wins over every other gate", () => {
    setup({ application: "x", planSelected: true, allocation: [{ region: "r", count: 1 }], submit: { status: "submitting" } });
    expect(chipValue("Status")).toBe("Submitting…");
    expect(chipVariantClass("Status")).toContain("--submitting");
  });

  it("Error wins over local validation gates", () => {
    // Even with a fully-valid form, an in-flight error stays visible
    // until the user changes something — submit feedback is the
    // relevant signal at that moment.
    setup({
      application: "x", planSelected: true,
      allocation: [{ region: "r", count: 1 }],
      submit: { status: "error", code: "INSUFFICIENT_CAPACITY" },
    });
    expect(chipValue("Status")).toBe("Insufficient capacity");
    expect(chipVariantClass("Status")).toContain("--error");
  });

  it("Unknown error code surfaces the code so operators see what backend returned", () => {
    setup({
      application: "x", planSelected: true,
      allocation: [{ region: "r", count: 1 }],
      submit: { status: "error", code: "WEIRD_BACKEND_CODE" },
    });
    expect(chipValue("Status")).toBe("WEIRD_BACKEND_CODE");
    expect(chipVariantClass("Status")).toContain("--error");
  });

  it("Needs application when application missing", () => {
    setup();
    expect(chipValue("Status")).toBe("Needs application");
    expect(chipVariantClass("Status")).toContain("--needs");
  });

  it("Needs plan when application set but plan missing", () => {
    setup({ application: "checkout-svc" });
    expect(chipValue("Status")).toBe("Needs plan");
    expect(chipVariantClass("Status")).toContain("--needs");
  });

  it("Needs fleet when application + plan set but allocation empty", () => {
    setup({ application: "checkout-svc", planSelected: true });
    expect(chipValue("Status")).toBe("Needs fleet");
    expect(chipVariantClass("Status")).toContain("--needs");
  });

  it("Ready when application + plan + ≥1 pod allocated", () => {
    setup({
      application: "checkout-svc",
      planSelected: true,
      allocation: [{ region: "local-east-1", count: 1 }],
    });
    expect(chipValue("Status")).toBe("Ready");
    expect(chipVariantClass("Status")).toContain("--ready");
  });
});

describe("RunSummaryChips — accessibility", () => {
  it("Status chip is announced to assistive tech via role=status + aria-live", () => {
    setup({ application: "checkout-svc", planSelected: true,
            allocation: [{ region: "r", count: 1 }] });
    const statusChip = screen.getByText("Status").closest(".runSummaryChip");
    expect(statusChip).toHaveAttribute("role", "status");
    const valueEl = within(statusChip as HTMLElement).getByText("Ready");
    expect(valueEl).toHaveAttribute("aria-live", "polite");
  });

  it("Total workers / Regions / Plan chips do NOT carry role=status — only Status does", () => {
    setup({ allocation: [{ region: "r", count: 2 }] });
    const podsChip = screen.getByText("Total workers").closest(".runSummaryChip");
    expect(podsChip).not.toHaveAttribute("role");
    const regionsChip = screen.getByText("Regions").closest(".runSummaryChip");
    expect(regionsChip).not.toHaveAttribute("role");
    const planChip = screen.getByText("Plan").closest(".runSummaryChip");
    expect(planChip).not.toHaveAttribute("role");
  });

  it("container has an aria-label naming the region", () => {
    const { container } = setup();
    const root = container.querySelector(".runSummaryChips");
    expect(root).toHaveAttribute("aria-label", "Run summary");
  });

  it("no axe violations in idle (Needs application) or Ready state", async () => {
    const { container, rerender } = setup();
    expect(await axe(container)).toHaveNoViolations();
    rerender(<RunSummaryChips
      application="checkout-svc" planName="checkout-load" planSelected
      allocation={[{ region: "local-east-1", count: 1 }]}
      submit={{ status: "idle" }}
    />);
    expect(await axe(container)).toHaveNoViolations();
  });
});
