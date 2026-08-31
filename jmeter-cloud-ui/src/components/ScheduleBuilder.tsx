import { useEffect, useMemo, useRef, useState } from "react";

import { InfoTip } from "./InfoTip";
import {
  browserTimeZone,
  buildCron,
  commonZones,
  CRON_EXAMPLES,
  cronToSimple,
  DEFAULT_SIMPLE,
  describeCron,
  formatInZone,
  type Frequency,
  nextCronFires,
  type SimpleSchedule,
  WEEKDAY_OPTIONS,
  zoneLabel,
} from "../lib/cron";

/**
 * Friendly schedule + timezone picker shared by both create dialogs. Two modes:
 *
 *  • Simple (default) — plain-language presets (Every day / weekday / week / month /
 *    hour / N minutes) + a time picker. Generates the cron for you.
 *  • Advanced — raw 5-field cron with an examples (ⓘ) popover for people who know it.
 *
 * Everything (the live "next fires" preview + the summary) is shown in the selected
 * timezone, which defaults to the browser's own zone — so EST/IST users never convert
 * to UTC in their head. Emits `{ cronExpression, timeZone }` to the parent on change;
 * the parent stores it and submits it. Uncontrolled-with-initial-value: `value` seeds
 * the initial state only (both dialogs are create-only).
 */

export interface ScheduleValue {
  cronExpression: string;
  timeZone: string;
}

export interface ScheduleBuilderProps {
  value: ScheduleValue;
  onChange: (next: ScheduleValue) => void;
  idPrefix?: string;
  /** Initial Simple-mode time of day ("HH:MM"); e.g. reports default to a morning send. */
  defaultTime?: string;
}

const FREQ_OPTIONS: ReadonlyArray<{ value: Frequency; label: string }> = [
  { value: "daily", label: "Every day" },
  { value: "weekdays", label: "Every weekday (Mon–Fri)" },
  { value: "weekly", label: "Every week on…" },
  { value: "monthly", label: "Every month on…" },
  { value: "hourly", label: "Every hour" },
  { value: "everyNMinutes", label: "Every N minutes" },
];

function pad2(n: number): string {
  return n.toString().padStart(2, "0");
}

export function ScheduleBuilder({ value, onChange, idPrefix = "sched", defaultTime = "02:00" }: ScheduleBuilderProps) {
  // Seed from the incoming expression: if it's a shape Simple mode can represent,
  // open in Simple with those values; otherwise open in Advanced showing the raw
  // cron. Makes both create (default expr) and edit (existing expr) land in the
  // most editable mode. `value` is create/edit-only so this seed is stable.
  const seededSimple = cronToSimple(value.cronExpression);
  const [mode, setMode] = useState<"simple" | "advanced">(seededSimple ? "simple" : "advanced");
  const [simple, setSimple] = useState<SimpleSchedule>(seededSimple ?? { ...DEFAULT_SIMPLE, time: defaultTime });
  const [rawCron, setRawCron] = useState(value.cronExpression || buildCron({ ...DEFAULT_SIMPLE, time: defaultTime }));
  const [timeZone, setTimeZone] = useState(value.timeZone || browserTimeZone());

  const cronExpression = mode === "simple" ? buildCron(simple) : rawCron.trim();

  // Emit upward without a render loop regardless of how the parent passes onChange.
  const onChangeRef = useRef(onChange);
  useEffect(() => { onChangeRef.current = onChange; });
  useEffect(() => {
    onChangeRef.current({ cronExpression, timeZone });
  }, [cronExpression, timeZone]);

  const zones = useMemo(() => commonZones(), []);
  const summary = useMemo(() => describeCron(cronExpression), [cronExpression]);
  const fires = useMemo(
    () => nextCronFires(cronExpression, 5, new Date(), timeZone),
    [cronExpression, timeZone],
  );

  const minute = Number(simple.time.split(":")[1] ?? "0");

  function patch(p: Partial<SimpleSchedule>) {
    setSimple((s) => ({ ...s, ...p }));
  }

  function toAdvanced() {
    setRawCron(cronExpression); // seed from the current simple selection
    setMode("advanced");
  }

  const ids = {
    freq: `${idPrefix}Freq`, time: `${idPrefix}Time`, dow: `${idPrefix}Dow`,
    dom: `${idPrefix}Dom`, min: `${idPrefix}Min`, everyN: `${idPrefix}EveryN`,
    cron: `${idPrefix}Cron`, tz: `${idPrefix}Tz`,
  };

  return (
    <div className="scheduleBuilder">
      <div className="scheduleBuilder__head">
        <span className="formLabel">Schedule *</span>
        <div className="scheduleBuilder__modeToggle" role="tablist" aria-label="Schedule mode">
          <button
            type="button" role="tab" aria-selected={mode === "simple"}
            className={`btn btn--sm ${mode === "simple" ? "btn--primary" : "btn--ghost"}`}
            onClick={() => setMode("simple")}
          >
            Simple
          </button>
          <button
            type="button" role="tab" aria-selected={mode === "advanced"}
            className={`btn btn--sm ${mode === "advanced" ? "btn--primary" : "btn--ghost"}`}
            onClick={toAdvanced}
          >
            Advanced
          </button>
        </div>
      </div>

      {mode === "simple" ? (
        <div className="scheduleBuilder__simple">
          <div className="formField">
            <label htmlFor={ids.freq}>Repeat</label>
            <select
              id={ids.freq}
              value={simple.frequency}
              onChange={(e) => patch({ frequency: e.target.value as Frequency })}
            >
              {FREQ_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>

          {simple.frequency === "weekly" && (
            <div className="formField">
              <label htmlFor={ids.dow}>Day of week</label>
              <select id={ids.dow} value={simple.weekday} onChange={(e) => patch({ weekday: Number(e.target.value) })}>
                {WEEKDAY_OPTIONS.map((d) => <option key={d.value} value={d.value}>{d.label}</option>)}
              </select>
            </div>
          )}

          {simple.frequency === "monthly" && (
            <div className="formField">
              <label htmlFor={ids.dom}>Day of month</label>
              <input
                id={ids.dom} type="number" min={1} max={31} value={simple.dayOfMonth}
                onChange={(e) => patch({ dayOfMonth: Number(e.target.value) })}
              />
              <small>1–31. Months without this day are skipped.</small>
            </div>
          )}

          {simple.frequency === "hourly" ? (
            <div className="formField">
              <label htmlFor={ids.min}>Minute past the hour</label>
              <input
                id={ids.min} type="number" min={0} max={59} value={minute}
                onChange={(e) => patch({ time: `00:${pad2(Math.max(0, Math.min(59, Number(e.target.value))))}` })}
              />
            </div>
          ) : simple.frequency === "everyNMinutes" ? (
            <div className="formField">
              <label htmlFor={ids.everyN}>Every N minutes</label>
              <input
                id={ids.everyN} type="number" min={1} max={59} value={simple.everyN}
                onChange={(e) => patch({ everyN: Number(e.target.value) })}
              />
            </div>
          ) : (
            <div className="formField">
              <label htmlFor={ids.time}>Time</label>
              <input id={ids.time} type="time" value={simple.time} onChange={(e) => patch({ time: e.target.value })} />
            </div>
          )}
        </div>
      ) : (
        <div className="formField">
          <div className="scheduleBuilder__cronLabel">
            <label htmlFor={ids.cron}>CRON expression</label>
            <InfoTip label="Show cron examples">
              {(close) => (
                <ul className="cronExamples" aria-label="Cron examples">
                  {CRON_EXAMPLES.map((ex) => (
                    <li key={ex.expr}>
                      <button type="button" className="cronExamples__pick" onClick={() => { setRawCron(ex.expr); close(); }}>
                        <code>{ex.expr}</code>
                        <span className="ink-soft">{ex.label}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </InfoTip>
          </div>
          <input
            id={ids.cron} type="text" className="mono" value={rawCron}
            onChange={(e) => setRawCron(e.target.value)} placeholder="0 2 * * *"
          />
          <small><code>min hour day month weekday</code> (5-field). Validated on save.</small>
        </div>
      )}

      <div className="formField">
        <div className="formField__labelRow">
          <label htmlFor={ids.tz}>Time zone</label>
          <InfoTip label="About time zone">
            Defaults to your timezone — fire times below are shown in it, so
            there is no UTC math to do in your head.
          </InfoTip>
        </div>
        <select id={ids.tz} value={timeZone} onChange={(e) => setTimeZone(e.target.value)}>
          {zones.map((z) => <option key={z} value={z}>{zoneLabel(z)}</option>)}
          {!zones.includes(timeZone) && <option value={timeZone}>{zoneLabel(timeZone)}</option>}
        </select>
      </div>

      <div className="schedulePreview" aria-live="polite">
        <strong>{summary ?? <>Runs: <code className="mono">{cronExpression}</code></>}</strong>
        {fires.length > 0 ? (
          <ul className="schedulePreview__list">
            {fires.map((d, i) => <li key={i}>{formatInZone(d, timeZone)}</li>)}
          </ul>
        ) : (
          <p className="ink-soft" style={{ margin: "0.25rem 0 0", fontSize: "0.8rem" }}>
            Preview available for standard 5-field expressions; the server validates all forms on save.
          </p>
        )}
      </div>
    </div>
  );
}
