import { describe, expect, it } from "vitest";

import { buildCron, cronToSimple, describeCron, nextCronFires } from "../cron";

describe("nextCronFires", () => {
  it("computes daily fires for a 5-field unix cron (UTC)", () => {
    const from = new Date("2026-01-01T00:00:00Z");
    const fires = nextCronFires("0 2 * * *", 3, from, "UTC");
    expect(fires.map((d) => d.toISOString())).toEqual([
      "2026-01-01T02:00:00.000Z",
      "2026-01-02T02:00:00.000Z",
      "2026-01-03T02:00:00.000Z",
    ]);
  });

  it("returns [] for a 6-field or unparseable expression (preview hides)", () => {
    expect(nextCronFires("0 0 2 * * *", 3, new Date(), "UTC")).toEqual([]);
    expect(nextCronFires("nonsense", 3, new Date(), "UTC")).toEqual([]);
  });

  it("honours step + range fields", () => {
    const from = new Date("2026-01-01T00:00:00Z");
    const fires = nextCronFires("0 */6 * * *", 4, from, "UTC");
    expect(fires.map((d) => d.getUTCHours())).toEqual([6, 12, 18, 0]);
  });

  it("interprets the cron in the chosen timezone (09:00 America/New_York → 14:00Z in winter)", () => {
    const from = new Date("2026-01-01T00:00:00Z");
    const [first] = nextCronFires("0 9 * * *", 1, from, "America/New_York");
    // 9:00 EST (UTC−5) on 2026-01-01 → 14:00Z.
    expect(first.toISOString()).toBe("2026-01-01T14:00:00.000Z");
  });

  it("interprets the cron in IST (09:00 Asia/Kolkata → 03:30Z)", () => {
    const from = new Date("2026-06-01T00:00:00Z");
    const [first] = nextCronFires("0 9 * * *", 1, from, "Asia/Kolkata");
    // 9:00 IST (UTC+5:30) → 03:30Z.
    expect(first.toISOString()).toBe("2026-06-01T03:30:00.000Z");
  });
});

describe("buildCron", () => {
  const base = { time: "09:30", weekday: 1, dayOfMonth: 1, everyN: 15 };
  it("daily", () => expect(buildCron({ ...base, frequency: "daily" })).toBe("30 9 * * *"));
  it("weekdays", () => expect(buildCron({ ...base, frequency: "weekdays" })).toBe("30 9 * * 1-5"));
  it("weekly", () => expect(buildCron({ ...base, frequency: "weekly", weekday: 5 })).toBe("30 9 * * 5"));
  it("monthly", () => expect(buildCron({ ...base, frequency: "monthly", dayOfMonth: 15 })).toBe("30 9 15 * *"));
  it("hourly (minute from time)", () => expect(buildCron({ ...base, frequency: "hourly" })).toBe("30 * * * *"));
  it("everyNMinutes", () => expect(buildCron({ ...base, frequency: "everyNMinutes", everyN: 5 })).toBe("*/5 * * * *"));
});

describe("cronToSimple (inverse of buildCron, for edit)", () => {
  const base = { time: "09:30", weekday: 1, dayOfMonth: 1, everyN: 15 };
  it("round-trips every Simple frequency", () => {
    const cases = [
      { ...base, frequency: "daily" as const },
      { ...base, frequency: "weekdays" as const },
      { ...base, frequency: "weekly" as const, weekday: 5 },
      { ...base, frequency: "monthly" as const, dayOfMonth: 15 },
      { ...base, frequency: "hourly" as const },
      { ...base, frequency: "everyNMinutes" as const, everyN: 5 },
    ];
    for (const s of cases) {
      const round = cronToSimple(buildCron(s));
      expect(round, JSON.stringify(s)).not.toBeNull();
      // re-emitting the parsed shape yields the same expression
      expect(buildCron(round!)).toBe(buildCron(s));
    }
  });
  it("returns null for expressions Simple mode can't represent", () => {
    expect(cronToSimple("0 0 1 1 *")).toBeNull();   // month-specific
    expect(cronToSimple("0 9 * * 1,3")).toBeNull();  // multiple weekdays
    expect(cronToSimple("not a cron")).toBeNull();
  });
});

describe("describeCron", () => {
  it("names common presets", () => {
    expect(describeCron("0 9 * * *")).toBe("Every day at 9:00 AM");
    expect(describeCron("30 9 * * 1-5")).toBe("Every weekday (Mon–Fri) at 9:30 AM");
    expect(describeCron("0 22 * * 0")).toBe("Every Sunday at 10:00 PM");
    expect(describeCron("0 0 1 * *")).toBe("Monthly on day 1 at 12:00 AM");
    expect(describeCron("*/15 * * * *")).toBe("Every 15 minutes");
  });
  it("returns null when it can't confidently name the expression", () => {
    expect(describeCron("0 0 1 1 *")).toBeNull(); // month-specific
    expect(describeCron("nonsense")).toBeNull();
  });
});
