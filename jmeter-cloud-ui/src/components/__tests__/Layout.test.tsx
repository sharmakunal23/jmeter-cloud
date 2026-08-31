import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

vi.mock("../../hooks/usePlatformCapabilities", () => ({
  usePlatformCapabilities: () => ({ dynamicScalingEnabled: true, isStaticFleet: false }),
}));

import { Layout } from "../Layout";

/** jsdom reports scrollHeight 0; pin the page height a test needs. */
function setPageHeight(px: number) {
  Object.defineProperty(document.documentElement, "scrollHeight", {
    value: px, configurable: true,
  });
}

function footerEl(): HTMLElement {
  return screen.getByText("CCB Card Platform Services").closest("footer")!;
}

describe("Layout — adaptive footer", () => {
  it("short page (content + footer fit the viewport): footer is pinned visible and ignores the cursor", () => {
    setPageHeight(300); // under jsdom's 768 innerHeight — the page doesn't scroll
    render(<MemoryRouter><Layout /></MemoryRouter>);
    expect(footerEl().className).toContain("appFooter--visible");

    // Cursor far from the bottom — a pinned footer must not hide.
    fireEvent.mouseMove(window, { clientY: 10 });
    expect(footerEl().className).toContain("appFooter--visible");
  });

  it("long page: footer stays hidden until the cursor nears the viewport bottom, with hysteresis on the way out", () => {
    setPageHeight(2000);
    render(<MemoryRouter><Layout /></MemoryRouter>);
    expect(footerEl().className).not.toContain("appFooter--visible");

    // Reveal: cursor within 24px of the bottom edge.
    fireEvent.mouseMove(window, { clientY: window.innerHeight - 8 });
    expect(footerEl().className).toContain("appFooter--visible");

    // Hysteresis: still visible while the cursor is within the hide band…
    fireEvent.mouseMove(window, { clientY: window.innerHeight - 60 });
    expect(footerEl().className).toContain("appFooter--visible");

    // …and hidden once the cursor is clearly away from the bottom.
    fireEvent.mouseMove(window, { clientY: window.innerHeight - 200 });
    expect(footerEl().className).not.toContain("appFooter--visible");
  });

  it("re-pins when the page shrinks under the viewport (resize/content change)", () => {
    setPageHeight(2000);
    render(<MemoryRouter><Layout /></MemoryRouter>);
    expect(footerEl().className).not.toContain("appFooter--visible");

    setPageHeight(300);
    fireEvent(window, new Event("resize"));
    expect(footerEl().className).toContain("appFooter--visible");
  });
});
