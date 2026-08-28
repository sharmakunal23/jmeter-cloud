import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";

import { useInterval } from "../hooks/useInterval";
import { runsApi, type Run, type RunState } from "../api/runs";
import { RegionBadgeList } from "../components/RegionBadge";
import { TwoRunMetricsPanel, TWO_RUN_PALETTE } from "../components/TwoRunMetricsPanel";

/**
 * Two-run comparison view, mounted at {@code /runs?compare=A,B}.
 *
 * <p>HM-7 replaced the previous
 * per-run iframe layout with a single {@link TwoRunMetricsPanel}
 * fed by the new {@code /api/v1/runs/timeseries?ids=A,B} batch
 * endpoint. The page now compares <b>exactly two runs</b> — a
 * deliberate scope decision (2026-05-10) so the chart legend, color
 * palette and URL contract stay ergonomic.
 *
 * <p>The two-column header above the panel still polls each run's
 * {@code /status} every 5 s so badges, fleet sizes and timestamps stay
 * live while a comparison is open. Each column carries the colored
 * swatch matching the panel's chart legend so the operator can match
 * "this column" → "this line on the chart" at a glance.
 */
type CompareEntry =
  | { runId: string; status: "loading" }
  | { runId: string; status: "ok"; run: Run }
  | { runId: string; status: "error"; code: string; message: string };

const POLL_INTERVAL_MS = 5_000;

/** Strict 2-run UX (2026-05-10). */
const REQUIRED_RUN_COUNT = 2;

/** Terminal run states — once both runs are here, nothing changes, so stop polling. */
const TERMINAL_STATES: ReadonlySet<RunState> = new Set<RunState>(["COMPLETED", "FAILED", "ABORTED"]);

/**
 * @param runIds list of run ids from the URL `compare` param.
 * @param appName optional application context — when present, the back
 *   link points at the app's detail page so the operator returns to
 *   the table they came from. Falls back to `/applications` otherwise.
 */
export function RunsComparePage({ runIds, appName }: { runIds: string[]; appName?: string }) {
  const backHref = appName ? `/applications/${encodeURIComponent(appName)}` : "/applications";
  const backLabel = appName ? `← Back to ${appName}` : "← Back to runs";
  // Distinct + length validation up front. The compare URL is
  // operator-controlled (RunsListPage builds it from checkbox state,
  // but a hand-typed URL could carry duplicates / 1 / 3+); show a clear
  // error rather than half-rendering the panel.
  //
  // `runIds` is a FRESH array on every render (both call sites build it
  // with `parseCompareIds(...)` in their own render). Memoise `distinctIds`
  // on a value-equality key so its identity is stable across renders — without
  // this, `fetchAll` (useCallback below) got a new identity each render, its
  // effect re-ran on every render, and each state update from a resolved GET
  // kicked off two more GETs: a render→fetch→setState→render loop that floods
  // the Network tab with /runs/{id} requests. See RunsComparePage.test.tsx
  // ("does not re-fetch in a loop").
  const distinctIdsKey = runIds.filter((id) => id && id.length > 0).join(",");
  const distinctIds = useMemo<string[]>(
    () => (distinctIdsKey ? Array.from(new Set(distinctIdsKey.split(","))) : []),
    [distinctIdsKey],
  );
  const hasValidPair = distinctIds.length === REQUIRED_RUN_COUNT;

  const [entries, setEntries] = useState<CompareEntry[]>(() =>
    distinctIds.map((runId) => ({ runId, status: "loading" })),
  );

  const fetchAll = useCallback(() => {
    const ctl = new AbortController();
    distinctIds.forEach((runId, idx) => {
      runsApi
        .get(runId, ctl.signal)
        .then((run) => {
          setEntries((prev) => replaceAt(prev, idx, { runId, status: "ok", run }));
        })
        .catch((err: unknown) => {
          if (ctl.signal.aborted) return;
          const message = err instanceof Error ? err.message : String(err);
          const code =
            err && typeof err === "object" && "code" in err
              ? String((err as { code: unknown }).code)
              : "UNKNOWN";
          setEntries((prev) => {
            const existing = prev[idx];
            // Don't clobber a successful fetch with a transient blip.
            if (existing?.status === "ok") return prev;
            return replaceAt(prev, idx, { runId, status: "error", code, message });
          });
        });
    });
    return ctl;
  }, [distinctIds]);  // eslint-disable-line react-hooks/exhaustive-deps -- distinctIds derived from props

  useEffect(() => {
    if (!hasValidPair) return undefined;
    const ctl = fetchAll();
    return () => ctl.abort();
  }, [fetchAll, hasValidPair]);

  // Both runs settled into a terminal state → nothing more will change, so
  // pause the poll (passing null delay stops the timer). Comparing two
  // already-completed runs — the common case — does zero polling after the
  // first load.
  const bothTerminal =
    entries.length === REQUIRED_RUN_COUNT &&
    entries.every((e) => e.status === "ok" && TERMINAL_STATES.has(e.run.state));
  useInterval(fetchAll, hasValidPair && !bothTerminal ? POLL_INTERVAL_MS : null);

  if (!hasValidPair) {
    return (
      <section>
        <header className="pageHeader runsCompare__header">
          <div className="runsCompare__headerLeft">
            <Link to={backHref} className="runsCompare__backLink">{backLabel}</Link>
            <h1>Compare Results</h1>
          </div>
        </header>
        <p className="text--error">
          The comparison view needs <strong>exactly two distinct run ids</strong>; got{" "}
          {distinctIds.length} ({runIds.length === distinctIds.length
            ? "wrong count"
            : "after deduplicating"}). Pick two runs from the list and try again.
        </p>
      </section>
    );
  }

  const [idA, idB] = distinctIds as [string, string];
  const stateA = entries[0]?.status === "ok" ? entries[0].run.state : null;
  const stateB = entries[1]?.status === "ok" ? entries[1].run.state : null;

  return (
    <section>
      <header className="pageHeader runsCompare__header">
        {/* 2026-05-16 — back link promoted to the LEFT of the H1 for
            consistency with RunDetailPage / ApplicationDetailPage. */}
        <div className="runsCompare__headerLeft">
          <Link to={backHref} className="runsCompare__backLink">{backLabel}</Link>
          <h1>Compare Results</h1>
        </div>
      </header>

      <div
        className="compareGrid"
        style={{ ["--compareCols" as string]: REQUIRED_RUN_COUNT.toString() }}
      >
        <CompareColumn entry={entries[0]!} swatchColor={TWO_RUN_PALETTE.runA} />
        <CompareColumn entry={entries[1]!} swatchColor={TWO_RUN_PALETTE.runB} />
      </div>

      <div className="compareMetrics">
        <TwoRunMetricsPanel
          runIdA={idA}
          runIdB={idB}
          runStateA={stateA}
          runStateB={stateB}
        />
      </div>
    </section>
  );
}

function CompareColumn({
  entry,
  swatchColor,
}: {
  entry: CompareEntry;
  swatchColor: string;
}) {
  return (
    <article className="compareColumn">
      <header className="compareColumn__header">
        <span className="compareColumn__swatch" style={{ background: swatchColor }} aria-hidden="true" />
        <Link to={`/applications/_/runs/${entry.runId}`} className="mono compareColumn__link">
          {entry.runId}
        </Link>
        {entry.status === "ok" && (
          <span className={`badge badge--${badgeFor(entry.run.state)}`}>
            {entry.run.state}
          </span>
        )}
      </header>

      {entry.status === "loading" && <p>loading…</p>}
      {entry.status === "error" && (
        <p className="text--error">
          {entry.code}: {entry.message}
        </p>
      )}
      {entry.status === "ok" && (
        <dl className="defList defList--compact">
          <dt>Test plan</dt>
          <dd className="mono">{entry.run.testPlanBlobId}</dd>
          <dt>Regions</dt>
          <dd><RegionBadgeList run={entry.run} /></dd>
          <dt>Fleet</dt>
          <dd>
            {entry.run.fleetMembers.length} worker
            {entry.run.fleetMembers.length === 1 ? "" : "s"}
          </dd>
          <dt>Started</dt>
          <dd>{format(entry.run.startedAt ?? entry.run.createdAt)}</dd>
        </dl>
      )}
    </article>
  );
}

function badgeFor(state: RunState | string): "ok" | "warn" | "err" | "info" {
  switch (state) {
    case "RUNNING":
    case "STARTING":
    case "PREPARING":
    case "DRAINING":
      return "info";
    case "COMPLETED":
      return "ok";
    case "FAILED":
    case "ABORTED":
      return "err";
    default:
      return "warn";
  }
}

function format(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString();
}

function replaceAt<T>(arr: T[], idx: number, value: T): T[] {
  if (idx < 0 || idx >= arr.length) return arr;
  const next = arr.slice();
  next[idx] = value;
  return next;
}
