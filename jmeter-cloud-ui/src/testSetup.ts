import "@testing-library/jest-dom/vitest";
// Side-effect import: augments vitest's Assertion type with `toHaveNoViolations`.
import "vitest-axe/extend-expect";

import { expect } from "vitest";
import * as matchers from "vitest-axe/matchers";

// Wire vitest-axe's `toHaveNoViolations` matcher into vitest's expect.
// Per-test usage: const results = await axe(container); expect(results).toHaveNoViolations();
expect.extend(matchers);

// jsdom doesn't ship matchMedia. uPlot calls it at module-init time
// (for HiDPI ratio detection), so any test that transitively imports
// uPlot — including the RunStreamsPanel tests via MetricsTabPanel —
// crashes during module evaluation without this stub. Returning
// matches=false is the correct "not retina" answer for jsdom.
if (typeof window !== "undefined" && typeof window.matchMedia !== "function") {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener:    () => {},
      removeListener: () => {},
      addEventListener:    () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
}
