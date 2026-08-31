import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

vi.mock("../../hooks/usePlatformCapabilities", () => ({
  usePlatformCapabilities: () => ({ dynamicScalingEnabled: true, isStaticFleet: false }),
}));

import { Layout } from "../Layout";

describe("Layout — dock-style footer", () => {
  it("footer stays hidden until the cursor nears the viewport bottom, with hysteresis on the way out", () => {
    render(<MemoryRouter><Layout /></MemoryRouter>);
    const footer = screen.getByText("CCB Card Performance").closest("footer")!;
    expect(footer.className).not.toContain("appFooter--visible");

    // Reveal: cursor within 24px of the bottom edge.
    fireEvent.mouseMove(window, { clientY: window.innerHeight - 8 });
    expect(footer.className).toContain("appFooter--visible");

    // Hysteresis: still visible while the cursor is within the hide band…
    fireEvent.mouseMove(window, { clientY: window.innerHeight - 60 });
    expect(footer.className).toContain("appFooter--visible");

    // …and hidden once the cursor is clearly away from the bottom.
    fireEvent.mouseMove(window, { clientY: window.innerHeight - 200 });
    expect(footer.className).not.toContain("appFooter--visible");
  });
});
