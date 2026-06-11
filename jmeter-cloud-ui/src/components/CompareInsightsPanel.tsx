import type { CompareInsightFinding, CompareInsights } from "../api/ai";
import type { CompareInsightsStatus } from "../hooks/useCompareInsights";

/**
 * "✨ Explain the delta" column rendered to the RIGHT of
 * the two-run comparison charts (toggled from the header, next to "Reset
 * zoom"). Presentational — mirrors {@link RunInsightsPanel}; the owning
 * {@link TwoRunMetricsPanel} holds the {@link useCompareInsights} state and
 * auto-generates on open. Per-metric verdicts are colour-coded. Advisory, never
 * authoritative.
 */
export interface CompareInsightsPanelProps {
  status: CompareInsightsStatus;
  data: CompareInsights | null;
  /** Both runs have metric data — before that we show a "not yet" hint. */
  ready: boolean;
  onRegenerate: () => void;
  onClose: () => void;
}

export function CompareInsightsPanel({ status, data, ready, onRegenerate, onClose }: CompareInsightsPanelProps) {
  const loading = status.kind === "loading";
  const hasData = data !== null;

  return (
    <section className="aiPanel aiPanel--side" aria-label="AI comparison insights">
      <header className="aiPanel__header">
        <h3 className="aiPanel__title">✨ Explain the delta</h3>
        <div className="aiPanel__actions">
          {hasData && (
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={onRegenerate}
              disabled={loading}
              title="Re-run the comparison (bypasses the cache, counts against the daily limit)"
            >
              ↻ Re-evaluate
            </button>
          )}
          <button
            type="button"
            className="aiPanel__close"
            onClick={onClose}
            aria-label="Close AI comparison insights"
            title="Close"
          >
            ×
          </button>
        </div>
      </header>

      <div className="aiPanel__scroll">
        {loading && (
          <p className="aiPanel__loading" role="status">
            Claude is comparing the two runs…
          </p>
        )}

        {status.kind === "error" && (
          <p className={status.quotaHit ? "aiPanel__quota" : "text--error"} role="alert">
            {status.message}
          </p>
        )}

        {status.kind === "idle" && !ready && (
          <p className="aiPanel__hint">
            Available once both runs have metrics.
          </p>
        )}

        {hasData && (
          <div className="aiPanel__body">
            <p className="aiPanel__summary">{data!.summary}</p>

            {data!.findings.length > 0 && (
              <ul className="aiPanel__findings">
                {data!.findings.map((f, i) => (
                  <li key={i} className="aiPanel__finding">
                    <span className={`badge badge--${verdictBadge(f.verdict)}`}>
                      {f.verdict}
                    </span>
                    <div className="aiPanel__findingText">
                      <strong>{f.metric}</strong>
                      {f.delta && <span className="aiPanel__findingDetail"> — {f.delta}</span>}
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
              Claude can be wrong — verify against the charts.
            </p>
          </div>
        )}
      </div>
    </section>
  );
}

/** regression → err, improvement → ok, anything else → info. */
function verdictBadge(verdict: CompareInsightFinding["verdict"]): "err" | "ok" | "info" {
  const v = verdict.toLowerCase();
  if (v.includes("regress")) return "err";
  if (v.includes("improve")) return "ok";
  return "info";
}

function formatCachedAt(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toLocaleString();
}
