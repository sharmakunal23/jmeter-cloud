import type { RunInsightFinding, RunInsights } from "../api/ai";
import { useCopyToClipboard } from "../hooks/useCopyToClipboard";
import type { RunInsightsStatus } from "../hooks/useRunInsights";
import { runInsightsMarkdown } from "../lib/aiInsightText";

/**
 * "✨ AI insights" column rendered to the RIGHT of the
 * run-detail Metrics charts (toggled from the header, next to "Split by
 * region"). Presentational: the owning {@link MetricsTabPanel} holds the
 * {@link useRunInsights} state, auto-generates on open, and passes the result
 * down here. Advisory, never authoritative (the disclaimer says so).
 *
 * <p>The column is height-bound to the chart area and scrolls internally, so
 * opening it never pushes the page taller — everything stays on one screen.
 */
export interface RunInsightsPanelProps {
  status: RunInsightsStatus;
  data: RunInsights | null;
  /** ≥ 30 s of metric data exist — before that we show a "not yet" hint. */
  ready: boolean;
  /** Re-run the analysis (bypasses the cache, re-bills). */
  onRegenerate: () => void;
  /** Collapse the column (also toggled from the header button). */
  onClose: () => void;
}

export function RunInsightsPanel({ status, data, ready, onRegenerate, onClose }: RunInsightsPanelProps) {
  const loading = status.kind === "loading";
  const hasData = data !== null;
  const { status: copyStatus, copy } = useCopyToClipboard();

  return (
    <section className="aiPanel aiPanel--side" aria-label="AI insights">
      <header className="aiPanel__header">
        <h3 className="aiPanel__title">✨ AI insights</h3>
        <div className="aiPanel__actions">
          {hasData && (
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => copy(runInsightsMarkdown(data!))}
              title="Copy this analysis as Markdown"
            >
              {copyStatus === "copied" ? "\u2713 Copied"
                : copyStatus === "error" ? "Copy failed"
                : "\u29c9 Copy"}
            </button>
          )}
          {hasData && (
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={onRegenerate}
              disabled={loading}
              title="Re-run the analysis (bypasses the cache, counts against the daily limit)"
            >
              ↻ Re-evaluate
            </button>
          )}
          <button
            type="button"
            className="aiPanel__close"
            onClick={onClose}
            aria-label="Close AI insights"
            title="Close"
          >
            ×
          </button>
        </div>
      </header>

      <div className="aiPanel__scroll">
        {loading && (
          <p className="aiPanel__loading" role="status">
            Claude is reading your run…
          </p>
        )}

        {status.kind === "error" && (
          <p className={status.quotaHit ? "aiPanel__quota" : "text--error"} role="alert">
            {status.message}
          </p>
        )}

        {/* Idle + not enough data yet — the summary would just be "the run started". */}
        {status.kind === "idle" && !ready && (
          <p className="aiPanel__hint">
            Insights become available once the run has ~30 s of metrics.
          </p>
        )}

        {hasData && (
          <div className="aiPanel__body">
            {/* The analysis always reads the whole run, while the charts beside
                it follow the toolbar's range — say so rather than let the
                operator discover the two disagree. */}
            <p className="aiPanel__scope">Whole run, every label.</p>
            <p className="aiPanel__summary">{data!.summary}</p>

            {data!.findings.length > 0 && (
              <ul className="aiPanel__findings">
                {data!.findings.map((f, i) => (
                  <li key={i} className="aiPanel__finding">
                    <span className={`badge badge--${severityBadge(f.severity)}`}>
                      {f.severity}
                    </span>
                    <div className="aiPanel__findingText">
                      <strong>{f.title}</strong>
                      {f.detail && <span className="aiPanel__findingDetail"> — {f.detail}</span>}
                      {f.evidence && <span className="aiPanel__findingEvidence">{f.evidence}</span>}
                    </div>
                  </li>
                ))}
              </ul>
            )}

            <p className="aiPanel__meta">
              {data!.fromCache ? "cached" : "generated"}{" "}
              {formatCachedAt(data!.cachedAt)} · {data!.tokensIn}+{data!.tokensOut} tokens · {data!.model}
            </p>
            <p className="aiPanel__disclaimer">
              Claude can be wrong — check each finding's figure against the charts.
            </p>
          </div>
        )}
      </div>
    </section>
  );
}

/** info → info, warn → warn, crit → err (reuse the existing badge palette). */
function severityBadge(severity: RunInsightFinding["severity"]): "info" | "warn" | "err" {
  switch (severity) {
    case "warn": return "warn";
    case "crit": return "err";
    default:     return "info";
  }
}

function formatCachedAt(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toLocaleString();
}
