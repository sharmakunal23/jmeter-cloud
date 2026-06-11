import { describe, expect, it } from "vitest";
import { utilizationTier } from "../utilization";

describe("utilizationTier", () => {
  describe("happy path — three tiers at the boundaries", () => {
    it("returns 'low' below 50% claimed", () => {
      expect(utilizationTier(1, 5)).toBe("low");   // 20%
      expect(utilizationTier(2, 5)).toBe("low");   // 40%
    });

    it("returns 'mid' at 50% (boundary inclusive on the lower end)", () => {
      // 50% = 0.5; spec: <50% low, <80% mid → 50% lands on mid.
      expect(utilizationTier(5, 10)).toBe("mid");  // 50%
      expect(utilizationTier(7, 10)).toBe("mid");  // 70%
    });

    it("returns 'high' at exactly 80%", () => {
      // The spec checkpoint case: 4 of 5 idle → 80% → red/high.
      expect(utilizationTier(4, 5)).toBe("high");  // 80%
      expect(utilizationTier(8, 10)).toBe("high"); // 80%
    });

    it("returns 'high' for any value ≥ 80%", () => {
      expect(utilizationTier(9, 10)).toBe("high");
      expect(utilizationTier(10, 10)).toBe("high");
    });
  });

  describe("edge cases", () => {
    it("claimed === 0 → low (nothing taken from this region, regardless of idle)", () => {
      expect(utilizationTier(0, 0)).toBe("low");
      expect(utilizationTier(0, 5)).toBe("low");
      expect(utilizationTier(0, 100)).toBe("low");
    });

    it("idle === 0 with claimed > 0 → high (over-allocation)", () => {
      expect(utilizationTier(1, 0)).toBe("high");
      expect(utilizationTier(99, 0)).toBe("high");
    });

    it("claimed > idle → high (over-allocation; pct > 1)", () => {
      expect(utilizationTier(6, 5)).toBe("high");   // 120%
      expect(utilizationTier(20, 5)).toBe("high");
    });

    it("negative inputs are coerced safely (claimed ≤ 0 → low)", () => {
      // Defensive: shouldn't happen in production but the fn shouldn't NaN.
      expect(utilizationTier(-1, 5)).toBe("low");
    });
  });
});
