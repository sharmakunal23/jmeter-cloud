import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent } from "react";

import { runsApi } from "../api/runs";
import { useVisiblePolling, type PauseReason } from "../hooks/useVisiblePolling";

/**
 * Live log tail for a single fleet member + stream source. Step 19 introduced
 * the panel; UI-3 (2026-05-10) restructured it for the tabbed run-detail
 * page:
 *
 * <ul>
 *   <li>Dropped the collapsible header — the surrounding tab strip in
 *       {@code RunStreamsPanel} now owns visibility, so the panel is
 *       always "open" while it exists. Inactive tabs unmount the panel
 *       entirely, which is the real polling-pause mechanism at fleet
 *       scale (no inactive useEffect can fire).</li>
 *   <li>Added the {@code streamSource} prop — {@code 'console'} tails
 *       the orchestrator's in-memory ring of the JMeter child's
 *       stdout/stderr; {@code 'jmeter'} tails {@code jmeter.log} on
 *       disk. Forwarded as the {@code ?stream=} query parameter
 *       documented in UI-1.</li>
 *   <li>Migrated from {@code useInterval} to {@code useVisiblePolling}
 *       — polling now also pauses when the browser tab is hidden or
 *       the panel scrolls off-screen, on top of the existing manual
 *       pause toggle and run-terminal hard stop.</li>
 *   <li>Pause indicator surfaces the reason (manual / tab hidden /
 *       off-screen) so the operator never wonders "did the panel
 *       break, or did I just minimize the window?"</li>
 * </ul>
 */
const POLL_INTERVAL_MS = 2_000;
const DEFAULT_TAIL = 200;

/**
 * UX31 / UX32 (2026-05-17): exact-match log-level filter with five
 * options — ALL (raw), INFO (default), WARN, DEBUG, ERROR. Each option
 * accepts every common spelling variant of its severity:
 *
 * <ul>
 *   <li><b>ALL (raw)</b> — every line, including non-log4j2 stdout
 *       noise (summariser totals, plugin chatter, "Tidying up…"). The
 *       escape hatch when an operator needs to see exactly what
 *       JMeter wrote.</li>
 *   <li><b>INFO (default)</b> — log4j2 INFO lines.</li>
 *   <li><b>WARN</b> — log4j2 WARN <em>or</em> WARNING lines. Different
 *       loggers and plugins emit one or the other; we don't care which.</li>
 *   <li><b>DEBUG</b> — log4j2 DEBUG lines.</li>
 *   <li><b>ERROR</b> — log4j2 ERROR, EXCEPTION, or FATAL lines.
 *       Operators almost always mean "show me the stack traces", so
 *       all three roll up under one filter option.</li>
 * </ul>
 */
const LEVELS = ["ALL", "INFO", "WARN", "DEBUG", "ERROR"] as const;
type Level = (typeof LEVELS)[number];

const LEVEL_LABELS: Record<Level, string> = {
  ALL:   "ALL (raw)",
  INFO:  "INFO (default)",
  WARN:  "WARN",
  DEBUG: "DEBUG",
  ERROR: "ERROR",
};

/**
 * Standard log4j2 line: {@code YYYY-MM-DD HH:MM:SS,mmm LEVEL category: message}.
 * Alternation puts the longer variants first so {@code WARNING} doesn't
 * mis-match as {@code WARN} (with `\b` it's fine either way, but
 * explicit order is the safer convention).
 */
const LOG4J2_LINE = /^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2},\d{3}\s+(WARNING|EXCEPTION|TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\b/;

function passesLevelFilter(line: string, level: Level): boolean {
  if (level === "ALL") return true;
  const m = LOG4J2_LINE.exec(line);
  if (!m) return false; // Non-log4j2 line — drop unless ALL.
  const lineLevel = m[1];
  switch (level) {
    case "INFO":  return lineLevel === "INFO";
    case "WARN":  return lineLevel === "WARN"  || lineLevel === "WARNING";
    case "DEBUG": return lineLevel === "DEBUG";
    case "ERROR": return lineLevel === "ERROR" || lineLevel === "EXCEPTION" || lineLevel === "FATAL";
  }
}

interface LogTailPanelProps {
  runId: string;
  workerId: string;
  /** Backend stream selector — `console` is stdout/stderr, `jmeter` is jmeter.log. */
  streamSource: "console" | "jmeter";
  /**
   * When the run is in a terminal state, the parent passes
   * {@code terminal=true} so the hook shuts down its timer. The panel
   * still serves the last fetched body for inspection.
   */
  terminal?: boolean;
  /**
   * Pause + Refresh controls are only meaningful while the pod is
   * actively producing log output. The parent passes {@code false}
   * for PENDING / REQUESTED / ACCEPTED / terminal members so the
   * useless controls don't clutter the toolbar.
   */
  showRefreshControls?: boolean;
}

export function LogTailPanel({
  runId, workerId, streamSource,
  terminal = false,
  showRefreshControls = true,
}: LogTailPanelProps) {
  const [text, setText] = useState<string>("");
  const [error, setError] = useState<string | null>(null);
  const [paused, setPaused] = useState(false);
  const [tail, setTail] = useState<number>(DEFAULT_TAIL);
  const [level, setLevel] = useState<Level>("INFO");
  const [regex, setRegex] = useState<string>("");
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);

  const preRef = useRef<HTMLPreElement | null>(null);

  const fetchOnce = useCallback(() => {
    const ctl = new AbortController();
    runsApi
      .podLogs(runId, workerId, { tail, stream: streamSource }, ctl.signal)
      .then((body) => {
        setText(body);
        setError(null);
        setLastUpdated(new Date());
      })
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setError(err instanceof Error ? err.message : String(err));
      });
    return ctl;
  }, [runId, workerId, streamSource, tail]);

  // Initial fetch when mounted, when the worker / stream / tail changes,
  // or when the operator hits Refresh now.
  useEffect(() => {
    const ctl = fetchOnce();
    return () => ctl.abort();
  }, [fetchOnce]);

  // Visibility-aware refresh — gates: terminal, manual paused, browser
  // tab hidden, panel scrolled off-screen. Inactive in-page tabs are
  // handled by the parent (which unmounts this component entirely).
  const delayMs = terminal ? null : POLL_INTERVAL_MS;
  const { isPaused, pauseReason } = useVisiblePolling(fetchOnce, delayMs, {
    paused,
    targetRef: preRef,
    name: `${streamSource}:${workerId}`,
  });

  // ── Filtering ──────────────────────────────────────────────────────

  // Operator decision 2026-05-15 (smoke-fix-2): the Console stream
  // (orchestrator stdout/stderr ring buffer) is intentionally raw —
  // operators want every line, exactly as JMeter emitted it, with no
  // level-floor or regex filter UI. The JMeter stream (jmeter.log)
  // keeps the log4j2-aware filter so the operator can tame INFO chatter.
  const isRawConsole = streamSource === "console";

  const filtered = useMemo(() => {
    const lines = text.split(/\r?\n/);
    if (isRawConsole) {
      return {
        lines: lines.filter((line) => line.length > 0),
        regexError: null as string | null,
      };
    }
    let regexMatcher: RegExp | null = null;
    let regexError: string | null = null;
    if (regex.trim()) {
      try {
        regexMatcher = new RegExp(regex, "i");
      } catch (e) {
        regexError = e instanceof Error ? e.message : String(e);
      }
    }
    const matched = lines.filter((line) => {
      // Drop blank lines that the split adds for trailing newlines —
      // they show up as empty "matches" and dilute the count.
      if (line.length === 0) return false;
      if (!passesLevelFilter(line, level)) return false;
      if (regexMatcher && !regexMatcher.test(line)) return false;
      return true;
    });
    return { lines: matched, regexError };
  }, [text, level, regex, isRawConsole]);

  // ── Smart auto-scroll: stick to bottom only if already at bottom ───

  const stickyAtBottomRef = useRef(true);

  const handleScroll = (e: React.UIEvent<HTMLPreElement>) => {
    const el = e.currentTarget;
    // 8 px slack so a small font-line-height bump still counts as "at bottom".
    stickyAtBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 8;
  };

  useEffect(() => {
    const el = preRef.current;
    if (!el) return;
    if (stickyAtBottomRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [filtered.lines.length, filtered.lines]);

  // ── Render ─────────────────────────────────────────────────────────

  const totalLines = text ? text.split(/\r?\n/).length : 0;
  const matchedCount = filtered.lines.length;
  const hidden = totalLines - matchedCount;

  return (
    <div className="logTail">
      <header className="logTail__header">
        <span className="logTail__title">
          <code className="mono">{workerId}</code>
          <span className="logTail__sourceTag">{streamSource}</span>
        </span>
        <span className="logTail__status">
          {error ? (
            <span className="text--error">{error}</span>
          ) : (
            <>
              {hidden > 0
                ? `${matchedCount} of ${totalLines} lines`
                : `${totalLines} line${totalLines === 1 ? "" : "s"}`}
              {lastUpdated && (
                <> · last fetch {lastUpdated.toLocaleTimeString()}</>
              )}
              {isPaused && (
                <> · <span className="badge badge--warn" title={pauseTooltip(pauseReason)}>
                  {pauseLabel(pauseReason)}
                </span></>
              )}
            </>
          )}
        </span>
      </header>

      <div className="logTail__toolbar">
        {!isRawConsole && (
          <>
            <label
              title="Strict log-level filter on parsed log4j2 lines. ALL keeps every line including raw stdout. WARN matches WARN/WARNING; ERROR matches ERROR/EXCEPTION/FATAL."
            >
              Level&nbsp;
              <select value={level} onChange={(e: ChangeEvent<HTMLSelectElement>) => setLevel(e.target.value as Level)}>
                {LEVELS.map((l) => (
                  <option key={l} value={l}>{LEVEL_LABELS[l]}</option>
                ))}
              </select>
            </label>
            <label>
              Filter&nbsp;
              <input
                type="text"
                value={regex}
                onChange={(e) => setRegex(e.target.value)}
                placeholder="regex (case-insensitive)"
              />
            </label>
          </>
        )}
        <label>
          Tail&nbsp;
          <input
            type="number"
            min={1}
            max={10000}
            value={tail}
            onChange={(e) => setTail(Math.max(1, Math.min(10000, Number(e.target.value) || DEFAULT_TAIL)))}
            style={{ width: "5.5rem" }}
          />
        </label>
        <span className="logTail__spacer" />
        {showRefreshControls ? (
          <>
            <label>
              <input
                type="checkbox"
                checked={paused}
                onChange={(e) => setPaused(e.target.checked)}
              />
              &nbsp;Pause polling
            </label>
            <button type="button" className="btn btn--ghost" onClick={() => fetchOnce()}>
              Refresh now
            </button>
          </>
        ) : (
          <span className="logTail__inactiveHint ink-soft" title="Pause + Refresh return when the worker reaches RUNNING">
            Worker not running — controls hidden
          </span>
        )}
      </div>

      {filtered.regexError && (
        <p className="text--error logTail__regexError">
          regex error: {filtered.regexError}
        </p>
      )}

      <pre
        ref={preRef}
        className="logTail__pane"
        onScroll={handleScroll}
        // Pause-on-hover is intentionally gone in UI-3 — the visibility
        // hook already covers the off-screen case, and an over-eager
        // hover-pause was confusing when the operator's mouse drifted
        // across the panel while reading other parts of the page.
        tabIndex={0}
        aria-label={`live log tail for ${workerId} (${streamSource})`}
      >
        {filtered.lines.length === 0 ? (
          text === ""
            ? "(no logs yet)"
            : isRawConsole
              ? "(no logs yet)"
              : `(no lines match level=${level}${regex ? ` regex=${regex}` : ""})`
        ) : (
          filtered.lines.join("\n")
        )}
      </pre>
    </div>
  );
}

function pauseLabel(reason: PauseReason): string {
  switch (reason) {
    case "manual":         return "paused";
    case "delayNull":      return "paused — terminal";
    case "documentHidden": return "paused — tab hidden";
    case "offscreen":      return "paused — off-screen";
    case null:             return "paused";
  }
}

function pauseTooltip(reason: PauseReason): string {
  switch (reason) {
    case "manual":         return "Pause checkbox is on. Uncheck to resume.";
    case "delayNull":      return "Run is in a terminal state (COMPLETED / FAILED / ABORTED) — nothing new to fetch.";
    case "documentHidden": return "Browser tab is in the background. Polling resumes when the tab returns to the foreground.";
    case "offscreen":      return "Panel is scrolled out of view. Scroll back to resume polling.";
    case null:             return "Polling is paused.";
  }
}
