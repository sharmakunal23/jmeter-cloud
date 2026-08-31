import { beforeEach, describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { InfoTip } from "../InfoTip";

/**
 * jsdom does no layout, so the trigger's rect is supplied directly. What is
 * under test is the arithmetic that keeps the popover on screen — the part
 * that was wrong.
 */
function renderTipAt(rect: Partial<DOMRect>, viewport: { w: number; h: number }) {
  window.innerWidth = viewport.w;
  window.innerHeight = viewport.h;
  render(<InfoTip label="About it">Some help text.</InfoTip>);
  const trigger = screen.getByRole("button", { name: "About it" });
  trigger.getBoundingClientRect = () => ({
    left: 0, top: 0, right: 0, bottom: 0, width: 16, height: 16, x: 0, y: 0,
    toJSON: () => ({}), ...rect,
  }) as DOMRect;
  fireEvent.click(trigger);
  return screen.getByRole("note");
}

beforeEach(() => {
  window.innerWidth = 1024;
  window.innerHeight = 768;
});

describe("InfoTip — the popover stays on screen", () => {
  it("shifts left rather than running off the right edge", () => {
    // The regression: capping max-width did nothing, because CSS resolves
    // min-width (16rem) after it — the tip ran 163px past the viewport.
    const pop = renderTipAt({ left: 1087, right: 1103, top: 400, bottom: 416 }, { w: 1180, h: 900 });
    const left = Number.parseFloat(pop.style.left);
    const minWidth = Number.parseFloat(pop.style.minWidth);
    expect(left + minWidth).toBeLessThanOrEqual(1180);
    expect(left).toBeLessThan(1087);   // it moved
  });

  it("keeps its left edge at the trigger when there is room", () => {
    const pop = renderTipAt({ left: 100, right: 116, top: 200, bottom: 216 }, { w: 1600, h: 900 });
    expect(pop.style.left).toBe("100px");
  });

  it("never starts off the left edge either", () => {
    const pop = renderTipAt({ left: -40, right: -24, top: 200, bottom: 216 }, { w: 1600, h: 900 });
    expect(Number.parseFloat(pop.style.left)).toBeGreaterThanOrEqual(0);
  });

  it("flips above the trigger when there is more room up there", () => {
    const pop = renderTipAt({ left: 100, right: 116, top: 700, bottom: 716 }, { w: 1600, h: 800 });
    expect(pop.style.bottom).toBeTruthy();
    expect(pop.style.top).toBe("");
  });

  it("sits below when there is more room down there", () => {
    const pop = renderTipAt({ left: 100, right: 116, top: 100, bottom: 116 }, { w: 1600, h: 800 });
    expect(pop.style.top).toBe("122px");
    expect(pop.style.bottom).toBe("");
  });

  it("shrinks below its 16rem minimum on a window narrower than that", () => {
    const pop = renderTipAt({ left: 10, right: 26, top: 100, bottom: 116 }, { w: 200, h: 700 });
    expect(Number.parseFloat(pop.style.minWidth)).toBeLessThanOrEqual(200);
    expect(Number.parseFloat(pop.style.left) + Number.parseFloat(pop.style.minWidth))
      .toBeLessThanOrEqual(200);
  });
});
