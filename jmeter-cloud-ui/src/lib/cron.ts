/**
 * Shared cron + timezone helpers for the Automation schedule UX. Pure, dependency-free,
 * and timezone-aware via the built-in `Intl` APIs (no moment/cron-parser dependency).
 *
 * The `<ScheduleBuilder>` and both create dialogs use these so there's one source of
 * truth for: generating cron from plain-language presets (`buildCron`), describing a
 * cron in words (`describeCron`), computing + formatting the next fire times in a chosen
 * IANA timezone (`nextCronFires` / `formatInZone`), and the timezone picker list.
 *
 * The generated expressions are standard 5-field unix cron, which the backend
 * (`CronSchedule` → Spring `CronExpression`) accepts and normalises to 6 fields.
 */

// ── Timezone helpers ───────────────────────────────────────────────────────

/** The browser's IANA zone (e.g. "America/New_York"), or "UTC" if undeterminable. */
export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}

// Curated set covering the team's zones (US + IST) plus a few common others.
const CURATED_ZONES = [
  "America/New_York",
  "America/Chicago",
  "America/Denver",
  "America/Los_Angeles",
  "Asia/Kolkata",
  "Europe/London",
  "Europe/Berlin",
  "Asia/Singapore",
  "Australia/Sydney",
  "UTC",
];

/** Common zones with the browser's own zone guaranteed present + first (so the
 *  default selection is always "the user's own time"). */
export function commonZones(): string[] {
  const tz = browserTimeZone();
  return [tz, ...CURATED_ZONES.filter((z) => z !== tz)];
}

/** "America/New_York · EDT · now 2:31 PM" — orients the user without forcing a
 *  mental UTC conversion. Falls back to the raw id if Intl rejects the zone. */
export function zoneLabel(tz: string, now: Date = new Date()): string {
  try {
    const time = new Intl.DateTimeFormat("en-US", {
      timeZone: tz, hour: "numeric", minute: "2-digit",
    }).format(now);
    const abbr = new Intl.DateTimeFormat("en-US", { timeZone: tz, timeZoneName: "short" })
      .formatToParts(now)
      .find((p) => p.type === "timeZoneName")?.value ?? "";
    return `${tz.replace(/_/g, " ")} · ${abbr} · now ${time}`;
  } catch {
    return tz;
  }
}

/** A fire time formatted in the given zone: "Mon, Jun 1, 9:00 AM EDT". */
export function formatInZone(date: Date, tz: string): string {
  try {
    return new Intl.DateTimeFormat("en-US", {
      timeZone: tz,
      weekday: "short", month: "short", day: "numeric",
      hour: "numeric", minute: "2-digit", timeZoneName: "short",
    }).format(date);
  } catch {
    return date.toISOString();
  }
}

// ── Cron field parsing (standard 5-field unix) ──────────────────────────────

function parseField(field: string, min: number, max: number): Set<number> | null {
  const out = new Set<number>();
  for (const part of field.split(",")) {
    let step = 1;
    let range = part;
    const slash = part.indexOf("/");
    if (slash >= 0) {
      step = Number(part.slice(slash + 1));
      range = part.slice(0, slash);
      if (!Number.isInteger(step) || step < 1) return null;
    }
    let lo: number;
    let hi: number;
    if (range === "*") { lo = min; hi = max; }
    else if (range.includes("-")) {
      const [a, b] = range.split("-").map(Number);
      lo = a; hi = b;
    } else {
      const n = Number(range);
      lo = n; hi = n;
    }
    if (!Number.isInteger(lo) || !Number.isInteger(hi) || lo < min || hi > max || lo > hi) return null;
    for (let v = lo; v <= hi; v += step) out.add(v);
  }
  return out;
}

const WEEKDAY_INDEX: Record<string, number> = {
  Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6,
};

/** Wall-clock fields of a UTC instant as observed in `fmt`'s timezone. */
function partsInZone(fmt: Intl.DateTimeFormat, d: Date) {
  const parts = fmt.formatToParts(d);
  const get = (t: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === t)?.value ?? "";
  return {
    minute: Number(get("minute")),
    hour: Number(get("hour")),
    day: Number(get("day")),
    month: Number(get("month")),
    weekday: WEEKDAY_INDEX[get("weekday")] ?? 0,
  };
}

/**
 * The next `count` fire times (as absolute `Date`s) for a standard 5-field cron,
 * interpreting the cron's minute/hour/day fields in `timeZone`. Returns `[]` for any
 * non-5-field or unparseable expression (the caller's preview just hides).
 *
 * Brute-forces minute-by-minute reading each candidate's wall clock in `timeZone`
 * via a cached `Intl` formatter, so DST transitions are handled by construction.
 * Bounded to ~1 year of minutes so a rare expression can't loop unbounded.
 */
export function nextCronFires(expr: string, count: number, from: Date, timeZone: string): Date[] {
  const fields = expr.trim().split(/\s+/);
  if (fields.length !== 5) return [];
  const [mF, hF, domF, monF, dowF] = fields;
  const minutes = parseField(mF, 0, 59);
  const hours = parseField(hF, 0, 23);
  const days = parseField(domF, 1, 31);
  const months = parseField(monF, 1, 12);
  const weekdays = parseField(dowF, 0, 7); // allow 7 = Sunday
  if (!minutes || !hours || !days || !months || !weekdays) return [];
  if (weekdays.has(7)) weekdays.add(0);
  const domStar = domF === "*";
  const dowStar = dowF === "*";

  let fmt: Intl.DateTimeFormat;
  try {
    fmt = new Intl.DateTimeFormat("en-US", {
      timeZone, hourCycle: "h23",
      weekday: "short", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit",
    });
  } catch {
    return [];
  }

  const out: Date[] = [];
  const t = new Date(from.getTime());
  t.setSeconds(0, 0);
  t.setMinutes(t.getMinutes() + 1);
  const CAP = 367 * 24 * 60;
  for (let i = 0; i < CAP && out.length < count; i++) {
    const p = partsInZone(fmt, t);
    if (minutes.has(p.minute) && hours.has(p.hour) && months.has(p.month)) {
      const domMatch = days.has(p.day);
      const dowMatch = weekdays.has(p.weekday);
      const dayOk = domStar && dowStar ? true
        : domStar ? dowMatch
        : dowStar ? domMatch
        : domMatch || dowMatch;
      if (dayOk) out.push(new Date(t));
    }
    t.setMinutes(t.getMinutes() + 1);
  }
  return out;
}

// ── Plain-language description ──────────────────────────────────────────────

const WEEKDAY_LONG = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];

function pad2(n: number): string {
  return n.toString().padStart(2, "0");
}

/** "9:00 AM" from numeric hour (0-23) + minute, or null if either isn't a plain integer. */
function clockWords(mStr: string, hStr: string): string | null {
  if (!/^\d+$/.test(mStr) || !/^\d+$/.test(hStr)) return null;
  const h = Number(hStr);
  const m = Number(mStr);
  if (h > 23 || m > 59) return null;
  const ampm = h < 12 ? "AM" : "PM";
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${h12}:${pad2(m)} ${ampm}`;
}

/**
 * A human summary of the common 5-field patterns the builder emits
 * ("Every weekday at 9:00 AM", "Monthly on day 1 at 12:00 AM", "Every 15 minutes").
 * Returns null when it can't confidently name the expression — the UI then shows
 * the raw cron instead.
 */
export function describeCron(expr: string): string | null {
  const f = expr.trim().split(/\s+/);
  if (f.length !== 5) return null;
  const [m, h, dom, mon, dow] = f;
  if (mon !== "*") return null; // month-specific → fall back to raw

  // Sub-hour cadences.
  if (h === "*" && dom === "*" && dow === "*") {
    const stepM = /^\*\/(\d+)$/.exec(m);
    if (stepM) return `Every ${stepM[1]} minutes`;
    if (m === "*") return "Every minute";
    if (/^\d+$/.test(m)) return `Every hour at :${pad2(Number(m))}`;
  }
  const at = clockWords(m, h);
  if (!at) return null;
  if (dom === "*" && dow === "*") return `Every day at ${at}`;
  if (dom === "*" && dow === "1-5") return `Every weekday (Mon–Fri) at ${at}`;
  if (dom === "*" && /^[0-7]$/.test(dow)) {
    return `Every ${WEEKDAY_LONG[Number(dow) % 7]} at ${at}`;
  }
  if (dow === "*" && /^\d+$/.test(dom)) return `Monthly on day ${dom} at ${at}`;
  return null;
}

// ── Simple-mode builder ─────────────────────────────────────────────────────

export type Frequency = "daily" | "weekdays" | "weekly" | "monthly" | "hourly" | "everyNMinutes";

export interface SimpleSchedule {
  frequency: Frequency;
  /** "HH:MM" 24h, used by daily/weekdays/weekly/monthly and the minute of hourly. */
  time: string;
  /** 0=Sun … 6=Sat, for weekly. */
  weekday: number;
  /** 1–31, for monthly. */
  dayOfMonth: number;
  /** step minutes, for everyNMinutes. */
  everyN: number;
}

export const DEFAULT_SIMPLE: SimpleSchedule = {
  frequency: "daily", time: "02:00", weekday: 1, dayOfMonth: 1, everyN: 15,
};

function parseTime(time: string): [number, number] {
  const m = /^(\d{1,2}):(\d{2})$/.exec(time.trim());
  if (!m) return [2, 0];
  const hh = Math.max(0, Math.min(23, Number(m[1])));
  const mm = Math.max(0, Math.min(59, Number(m[2])));
  return [hh, mm];
}

/** Generate a standard 5-field cron from Simple-mode state. */
export function buildCron(s: SimpleSchedule): string {
  const [hh, mm] = parseTime(s.time);
  switch (s.frequency) {
    case "daily":         return `${mm} ${hh} * * *`;
    case "weekdays":      return `${mm} ${hh} * * 1-5`;
    case "weekly":        return `${mm} ${hh} * * ${s.weekday}`;
    case "monthly":       return `${mm} ${hh} ${Math.max(1, Math.min(31, s.dayOfMonth))} * *`;
    case "hourly":        return `${mm} * * * *`;
    case "everyNMinutes": return `*/${Math.max(1, Math.min(59, s.everyN))} * * * *`;
  }
}

export const WEEKDAY_OPTIONS: ReadonlyArray<{ value: number; label: string }> =
  WEEKDAY_LONG.map((label, value) => ({ value, label }));

/**
 * Inverse of {@link buildCron} for the patterns Simple mode emits — lets the
 * builder re-open an existing schedule (edit) in Simple mode when it recognises
 * the shape, falling back to Advanced (raw cron) otherwise. Returns null for any
 * expression Simple mode can't represent.
 */
export function cronToSimple(expr: string): SimpleSchedule | null {
  const f = (expr ?? "").trim().split(/\s+/);
  if (f.length !== 5) return null;
  const [m, h, dom, mon, dow] = f;
  if (mon !== "*") return null;
  const base = { ...DEFAULT_SIMPLE };

  // Every N minutes — "*/N * * * *".
  const stepM = /^\*\/(\d+)$/.exec(m);
  if (stepM && h === "*" && dom === "*" && dow === "*") {
    const n = Number(stepM[1]);
    if (n >= 1 && n <= 59) return { ...base, frequency: "everyNMinutes", everyN: n };
    return null;
  }
  const mm = /^\d+$/.test(m) ? Number(m) : null;
  if (mm === null || mm > 59) return null;

  // Hourly — "mm * * * *" (minute carried in `time`).
  if (h === "*" && dom === "*" && dow === "*") {
    return { ...base, frequency: "hourly", time: `00:${pad2(mm)}` };
  }
  const hh = /^\d+$/.test(h) ? Number(h) : null;
  if (hh === null || hh > 23) return null;
  const time = `${pad2(hh)}:${pad2(mm)}`;

  if (dom === "*" && dow === "*") return { ...base, frequency: "daily", time };
  if (dom === "*" && dow === "1-5") return { ...base, frequency: "weekdays", time };
  if (dom === "*" && /^[0-6]$/.test(dow)) return { ...base, frequency: "weekly", time, weekday: Number(dow) };
  if (dow === "*" && /^\d+$/.test(dom) && Number(dom) >= 1 && Number(dom) <= 31) {
    return { ...base, frequency: "monthly", time, dayOfMonth: Number(dom) };
  }
  return null;
}

// ── Examples for the info popover ───────────────────────────────────────────

export const CRON_EXAMPLES: ReadonlyArray<{ expr: string; label: string }> = [
  { expr: "0 9 * * *",    label: "Every day at 9:00 AM" },
  { expr: "0 9 * * 1-5",  label: "Every weekday at 9:00 AM" },
  { expr: "30 18 * * 5",  label: "Every Friday at 6:30 PM" },
  { expr: "0 0 1 * *",    label: "1st of every month at midnight" },
  { expr: "0 */6 * * *",  label: "Every 6 hours" },
  { expr: "*/15 * * * *", label: "Every 15 minutes" },
  { expr: "0 22 * * 0",   label: "Every Sunday at 10:00 PM" },
];
