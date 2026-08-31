import { useEffect, useState, type ReactNode } from "react";

/**
 * One collapsible section of the Metrics tab. A collapsed section renders no
 * children, so the panels inside it mount nothing and fetch nothing — the
 * open sections are the only ones reading the database. Open/closed state is
 * remembered per section in the browser.
 */
export type MetricsSectionId =
  | "keyMetrics" | "throughput" | "errors" | "perLabel" | "aggregateReport"
  // The workflow execution's board. Separate ids so collapsing a section there
  // does not collapse the same-named one on a run's Metrics tab.
  | "wfKeyMetrics" | "wfSummary" | "wfThroughput" | "wfErrors";

const STORAGE_KEY = "jmeterCloud.metrics.sections";

const DEFAULT_OPEN: Record<MetricsSectionId, boolean> = {
  keyMetrics: true,
  throughput: true,
  errors: true,
  perLabel: false,
  aggregateReport: false,
  // The numbers and the throughput picture are what an operator opens the tab
  // for; the error charts are where they go once something looks wrong.
  wfKeyMetrics: true,
  wfSummary: true,
  wfThroughput: true,
  wfErrors: false,
};

function readOpen(id: MetricsSectionId): boolean {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Record<string, unknown>;
      if (typeof parsed[id] === "boolean") return parsed[id] as boolean;
    }
  } catch { /* private mode or bad JSON — use the default */ }
  return DEFAULT_OPEN[id];
}

function writeOpen(id: MetricsSectionId, open: boolean) {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? (JSON.parse(raw) as Record<string, unknown>) : {};
    parsed[id] = open;
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(parsed));
  } catch { /* private mode */ }
}

/** The open/closed state of one section, persisted per viewer. */
export function useSectionOpen(id: MetricsSectionId): [boolean, () => void] {
  const [open, setOpen] = useState<boolean>(() => readOpen(id));
  useEffect(() => { writeOpen(id, open); }, [id, open]);
  return [open, () => setOpen((v) => !v)];
}

export interface MetricsSectionProps {
  id: MetricsSectionId;
  title: string;
  open: boolean;
  onToggle: () => void;
  /** Short text beside the title — a count, a range, "loading…". */
  meta?: ReactNode;
  /** Controls that belong to this section, right-aligned in its header (shown while open). */
  controls?: ReactNode;
  children: ReactNode;
}

export function MetricsSection({ id, title, open, onToggle, meta, controls, children }: MetricsSectionProps) {
  const bodyId = `metricsSection-${id}`;
  return (
    <section className={`metricsSection ${open ? "metricsSection--open" : "metricsSection--closed"}`} aria-label={title}>
      <header className="metricsSection__header">
        <button
          type="button"
          className="metricsSection__toggle"
          aria-expanded={open}
          aria-controls={bodyId}
          onClick={onToggle}
        >
          <span className="metricsSection__chevron" aria-hidden="true">{open ? "▾" : "▸"}</span>
          <span className="metricsSection__title">{title}</span>
        </button>
        {meta && <span className="metricsSection__meta">{meta}</span>}
        {open && controls && <div className="metricsSection__controls">{controls}</div>}
      </header>
      {open && <div id={bodyId} className="metricsSection__body">{children}</div>}
    </section>
  );
}
