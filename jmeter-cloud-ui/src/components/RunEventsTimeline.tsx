import { useCallback, useEffect, useState } from "react";

import { useInterval } from "../hooks/useInterval";
import { Paginator } from "./Paginator";
import { formatRelative } from "../lib/time";
import { runsApi, type RunEvent, type RunEventType, type RunEventsListing } from "../api/runs";

/**
 * The per-run audit timeline, shown on its own Events tab. One
 * row per state-changing operator action (start / scale up / scale down /
 * drain), newest first. Paginated (25/page) because a long-running test can
 * accumulate many events. Polls the current page every 10s while the run is
 * non-terminal; static once terminal (the log is append-only and a finished
 * run never gains new events).
 */
const POLL_MS = 10_000;
const PAGE_SIZE = 25;

export function RunEventsTimeline({
  runId,
  isTerminal,
}: {
  runId: string;
  isTerminal: boolean;
}) {
  const [page, setPage] = useState(1); // 1-based
  const [data, setData] = useState<RunEventsListing | null>(null);
  const [error, setError] = useState<string | null>(null);

  const fetchPage = useCallback(
    (p: number) => {
      // Async IIFE with try/catch so the rejection is fully handled inside the
      // function — a dangling .catch() chain trips vitest's unhandled-rejection
      // guard on the error path.
      void (async () => {
        try {
          const listing = await runsApi.events(runId, {
            offset: (p - 1) * PAGE_SIZE,
            limit: PAGE_SIZE,
          });
          setData(listing);
          setError(null);
        } catch (e: unknown) {
          setError(e instanceof Error ? e.message : "failed to load events");
        }
      })();
    },
    [runId],
  );

  useEffect(() => {
    fetchPage(page);
  }, [fetchPage, page]);
  useInterval(() => fetchPage(page), isTerminal ? null : POLL_MS);

  if (error) {
    return <p className="ink-soft">Couldn't load audit events: {error}</p>;
  }
  if (data === null) {
    return <p className="ink-soft">Loading audit events…</p>;
  }
  if (data.total === 0) {
    return <p className="ink-soft">No audit events yet.</p>;
  }

  return (
    <>
      <table className="runsTable" aria-label="Run audit events">
        <thead>
          <tr>
            <th>Timestamp</th>
            <th>Action</th>
            <th>User</th>
            <th>Result</th>
            <th>Details</th>
          </tr>
        </thead>
        <tbody>
          {data.events.map((e) => (
            <tr key={e.eventId}>
              <td title={formatRelative(e.occurredAt)}>
                {new Date(e.occurredAt).toLocaleString()}
              </td>
              <td>
                <span className="chip">{eventActionLabel(e)}</span>
              </td>
              <td>
                {e.actor}
                {e.actorSource === "system" && (
                  <small className="ink-soft"> (System)</small>
                )}
              </td>
              <td>
                <span className={`chip ${resultChipClass(e.result)}`}>{e.result}</span>
              </td>
              <td className="ink-soft">{payloadSummary(e)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <Paginator
        page={page}
        pageSize={PAGE_SIZE}
        total={data.total}
        label="events"
        onChange={setPage}
      />
    </>
  );
}

const EVENT_TYPE_LABELS: Record<RunEventType, string> = {
  RUN_START: "Run started",
  SCALE_UP: "Scale up",
  SCALE_DOWN: "Scale down",
  DRAIN_WORKER: "Drain worker",
  ABORT: "Abort",
  STOP: "Stop",
  PROPERTIES_UPDATED: "Properties updated",
  DELETE: "Run hidden",
  DATA_FILES_REUSED: "Data files reused",
  DATA_FILES_UPLOADED: "Data files uploaded",
  TEST_PLAN_UPLOADED: "Test plan uploaded",
  PLUGINS_UPLOADED: "Plugins uploaded",
  ARTIFACTS_CLEARED: "Artifacts cleared",
  RUN_COMPLETED: "Run completed",
  RUN_FAILED: "Run failed",
  RUN_ABORTED: "Run aborted",
  WORKERS_RECYCLED: "Workers recycled",
  RESULTS_SAVED: "Results saved",
};

function eventTypeLabel(t: RunEventType): string {
  return EVENT_TYPE_LABELS[t] ?? t;
}

/**
 * Action-column label. Mostly the static eventType → label lookup, but a
 * WORKERS_RECYCLED event whose reason is DRAIN_AFTER_RUN is shown as
 * "Workers drained" — that policy drains the pods without spinning a
 * replacement, so "recycled" (which implies drain-and-replace) is misleading.
 * Drain-and-replace reasons (IMAGE_MISMATCH, MAX_RUNS, MAX_AGE, EVERY_RUN)
 * keep "Workers recycled".
 */
function eventActionLabel(e: RunEvent): string {
  if (e.eventType === "WORKERS_RECYCLED") {
    const reason = typeof e.payload?.reason === "string" ? e.payload.reason : "";
    if (reason === "DRAIN_AFTER_RUN") return "Workers drained";
  }
  return eventTypeLabel(e.eventType);
}

/** ok → green, partial → amber, failed/rejected → red, aborted → amber. */
function resultChipClass(result: string): string {
  if (result === "ok") return "chip--ok";
  if (result === "partial" || result === "aborted") return "chip--warn";
  if (result === "failed" || result.startsWith("rejected")) return "chip--err";
  return "";
}

/** A one-line, PII-free summary of the event payload, expandable to full JSON. */
function payloadSummary(e: RunEvent): string {
  const p = e.payload ?? {};
  switch (e.eventType) {
    case "RUN_START":
    case "SCALE_UP": {
      const granted = num(p.granted);
      const requested = num(p.requested);
      const regions = regionList(p.fleetAllocation ?? p.allocations);
      const counts = granted != null && requested != null ? `${granted}/${requested} workers` : "workers";
      return regions ? `${counts} · ${regions}` : counts;
    }
    case "SCALE_DOWN": {
      const drained = arr(p.drained).length;
      const skipped = arr(p.skipped).length;
      return skipped > 0 ? `drained ${drained}, skipped ${skipped}` : `drained ${drained}`;
    }
    case "DRAIN_WORKER": {
      const worker = typeof p.workerId === "string" ? p.workerId : "(worker)";
      const skipped = arr(p.skipped).length;
      return skipped > 0 ? `${worker} (skipped)` : worker;
    }
    case "RUN_COMPLETED":
    case "RUN_FAILED":
    case "RUN_ABORTED": {
      const reason = typeof p.reason === "string" && p.reason ? p.reason : null;
      const finalState = typeof p.finalState === "string" ? p.finalState : "";
      return reason ?? finalState;
    }
    case "WORKERS_RECYCLED": {
      const count = num(p.count) ?? arr(p.pods).length;
      const reason = typeof p.reason === "string" && p.reason ? p.reason : null;
      const noun = count === 1 ? "worker" : "workers";
      return reason ? `${count} ${noun} · ${reason}` : `${count} ${noun}`;
    }
    case "RESULTS_SAVED": {
      return typeof p.workerId === "string" && p.workerId ? p.workerId : "(worker)";
    }
    case "PROPERTIES_UPDATED": {
      const keys = p.properties && typeof p.properties === "object"
        ? Object.keys(p.properties as Record<string, unknown>) : [];
      const workers = arr(p.workerIds).length;
      const ok = num(p.ok);
      const failed = num(p.failed) ?? 0;
      const head = keys.length > 0 ? keys.join(", ") : "properties";
      const tail = failed > 0 && ok != null ? `${ok} ok, ${failed} failed`
        : `${workers} worker${workers === 1 ? "" : "s"}`;
      return `${head} → ${tail}`;
    }
    case "DELETE": {
      return typeof p.reason === "string" && p.reason ? p.reason : "";
    }
    case "DATA_FILES_REUSED":
    case "DATA_FILES_UPLOADED": {
      const reused = arr(p.reused).length;
      const downloaded = arr(p.downloaded).length;
      const parts: string[] = [];
      if (reused > 0) parts.push(`reused on ${reused}`);
      if (downloaded > 0) parts.push(`downloaded on ${downloaded}`);
      if (p.refreshRequested === true) parts.push("refresh forced");
      return parts.join(" · ");
    }
    case "TEST_PLAN_UPLOADED": {
      const workers = arr(p.workers).length;
      const blob = typeof p.testPlanBlobId === "string" ? p.testPlanBlobId.slice(0, 8) + "…" : "";
      return `${workers} worker${workers === 1 ? "" : "s"}${blob ? ` · ${blob}` : ""}`;
    }
    case "PLUGINS_UPLOADED": {
      const plugins = arr(p.plugins).filter((x): x is string => typeof x === "string");
      return plugins.length > 0 ? plugins.join(", ") : "plugins";
    }
    case "ARTIFACTS_CLEARED": {
      return typeof p.workerId === "string" && p.workerId ? p.workerId : "(worker)";
    }
    default:
      return "";
  }
}

function num(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

function arr(v: unknown): unknown[] {
  return Array.isArray(v) ? v : [];
}

/** Render `[{region,count}]` as "us-east×2, us-west×1". */
function regionList(v: unknown): string | null {
  if (!Array.isArray(v) || v.length === 0) return null;
  const parts = v
    .map((e) => {
      if (e && typeof e === "object" && "region" in e) {
        const region = String((e as { region: unknown }).region);
        const count = num((e as { count: unknown }).count);
        return count != null ? `${region}×${count}` : region;
      }
      return null;
    })
    .filter((s): s is string => s != null);
  return parts.length > 0 ? parts.join(", ") : null;
}
