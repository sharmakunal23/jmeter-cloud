import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { axe } from "vitest-axe";

import { VizPanelToolbar, type VizViewMode } from "../VizPanelToolbar";

interface SetupOpts {
  controlsHidden?: boolean;
  viewMode?: VizViewMode;
}

function setup(opts: SetupOpts = {}) {
  const onToggleControls = vi.fn();
  const onViewModeChange = vi.fn();
  const utils = render(
    <VizPanelToolbar
      controlsHidden={opts.controlsHidden ?? false}
      onToggleControls={onToggleControls}
      viewMode={opts.viewMode ?? "flow"}
      onViewModeChange={onViewModeChange}
    />,
  );
  return { ...utils, onToggleControls, onViewModeChange };
}

describe("VizPanelToolbar — Hide / Show Controls", () => {
  it("label flips between Hide and Show as controlsHidden flips", () => {
    const { rerender } = setup({ controlsHidden: false });
    expect(screen.getByRole("button", { name: /Hide Controls/ })).toBeInTheDocument();

    rerender(
      <VizPanelToolbar
        controlsHidden
        onToggleControls={vi.fn()}
        viewMode="flow"
        onViewModeChange={vi.fn()}
      />,
    );
    expect(screen.getByRole("button", { name: /Show Controls/ })).toBeInTheDocument();
  });

  it("clicking the button invokes onToggleControls", () => {
    const { onToggleControls } = setup();
    fireEvent.click(screen.getByRole("button", { name: /Hide Controls/ }));
    expect(onToggleControls).toHaveBeenCalledTimes(1);
  });

  it("aria-pressed reflects the controlsHidden state", () => {
    const { rerender } = setup({ controlsHidden: false });
    expect(screen.getByRole("button", { name: /Hide Controls/ })).toHaveAttribute("aria-pressed", "false");

    rerender(
      <VizPanelToolbar
        controlsHidden
        onToggleControls={vi.fn()}
        viewMode="flow"
        onViewModeChange={vi.fn()}
      />,
    );
    expect(screen.getByRole("button", { name: /Show Controls/ })).toHaveAttribute("aria-pressed", "true");
  });
});

// Flow / Form toggle removed. The form + diagram now
// render side-by-side always. The toolbar only owns Hide / Show Controls.

describe("VizPanelToolbar — accessibility", () => {
  it("is identified as a toolbar to assistive tech", () => {
    setup();
    expect(screen.getByRole("toolbar", { name: "Visualization panel controls" })).toBeInTheDocument();
  });

  it("has no axe violations in the default state", async () => {
    const { container } = setup();
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });
});
