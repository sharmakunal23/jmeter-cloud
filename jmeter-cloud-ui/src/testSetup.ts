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

// jsdom implements no canvas rendering at all: `getContext("2d")` returns null
// (logging "Not implemented"), and `Path2D` is undefined. uPlot takes the
// context once at construction and dereferences it in the redraw it schedules
// asynchronously, so a chart that mounts in a test throws
// `Cannot read properties of null (reading 'clearRect')` from a microtask —
// after the test that rendered it has passed, which is why it surfaced as an
// unhandled error rather than a failure.
//
// The alternative is the `canvas` npm package, a native build dependency for
// pixels no assertion reads. These stubs let uPlot run its real draw path and
// throw the output away.
//
// A caller that asks to READ the canvas back still gets null. axe-core requests
// `{willReadFrequently: true}` to sample colours for its contrast rules; handing
// it a stub would have it grade contrast against pixels nobody drew — inventing
// a11y results — and it also drags jsdom into `getComputedStyle(el, "::before")`,
// which it cannot do either. Draw-only callers are served; readers are refused.
if (typeof globalThis.Path2D !== "function") {
  class Path2DStub {
    addPath() {}
    closePath() {}
    moveTo() {}
    lineTo() {}
    bezierCurveTo() {}
    quadraticCurveTo() {}
    arc() {}
    arcTo() {}
    ellipse() {}
    rect() {}
    roundRect() {}
  }
  Object.defineProperty(globalThis, "Path2D", { writable: true, value: Path2DStub });
}

if (typeof HTMLCanvasElement !== "undefined") {
  const contexts = new WeakMap<HTMLCanvasElement, unknown>();
  HTMLCanvasElement.prototype.getContext = function getContext(
    this: HTMLCanvasElement,
    kind: string,
    attrs?: CanvasRenderingContext2DSettings,
  ) {
    if (kind !== "2d" || attrs?.willReadFrequently) return null;
    let ctx = contexts.get(this);
    if (!ctx) {
      // A property bag that answers every method call with a no-op and
      // remembers what is assigned to it (uPlot reads back `ctx.font` and
      // `ctx.fillStyle` between draws).
      const assigned = new Map<string | symbol, unknown>();
      ctx = new Proxy({} as Record<string | symbol, unknown>, {
        get: (_t, prop) => {
          if (prop === "canvas") return this;
          if (assigned.has(prop)) return assigned.get(prop);
          switch (prop) {
            case "measureText":          return () => ({ width: 0 });
            case "createLinearGradient":
            case "createRadialGradient":
            case "createPattern":        return () => ({ addColorStop: () => {} });
            case "getImageData":         return () => ({ data: new Uint8ClampedArray(4) });
            case "getLineDash":          return () => [];
            default:                     return () => {};
          }
        },
        set: (_t, prop, value) => {
          assigned.set(prop, value);
          return true;
        },
      });
      contexts.set(this, ctx);
    }
    return ctx as unknown as CanvasRenderingContext2D;
  } as HTMLCanvasElement["getContext"];
}
