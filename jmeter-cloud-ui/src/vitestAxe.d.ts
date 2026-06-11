// Augments vitest's Assertion type with vitest-axe's matchers.
//
// The vitest-axe@0.1.0 package's own `extend-expect.d.ts` augments
// `Vi.Assertion` (legacy vitest namespace), but vitest 4.x exposes
// `Assertion` via module augmentation on the `'vitest'` package.
// Without this shim, `expect(results).toHaveNoViolations()` is a
// runtime success but a tsc compile error.
import "vitest";

declare module "vitest" {
  interface Assertion<T = unknown> {
    toHaveNoViolations(): T;
  }
  interface AsymmetricMatchersContaining {
    toHaveNoViolations(): void;
  }
}
