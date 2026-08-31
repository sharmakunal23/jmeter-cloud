import { Link } from "react-router-dom";

import type { PluginSummary } from "../api/plugins";
import { InfoTip } from "./InfoTip";

/**
 * The launcher's run-scoped plugin selector (UX-DYNAMICS T3): jars picked
 * from the global library are staged onto every worker of the run before
 * JMeter starts. Ids hydrated from a template that no longer exist in the
 * library render as warn chips and are excluded from the launch — plugins
 * are additive, so a stale template never blocks a run.
 */
export interface RunPluginsFieldProps {
  /** The library, or null while it is loading / errored (parent owns the fetch). */
  plugins: PluginSummary[] | null;
  loading: boolean;
  error: string | null;
  /** Selected plugin ids (template-hydrated ids included, known or not). */
  value: string[];
  onChange: (next: string[]) => void;
  /** Hydrated ids absent from the library — warn chips, excluded from launch. */
  unknownIds: string[];
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function RunPluginsField({
  plugins, loading, error, value, onChange, unknownIds,
}: RunPluginsFieldProps) {
  const byId = new Map((plugins ?? []).map((p) => [p.pluginId, p]));
  const available = (plugins ?? []).filter((p) => !value.includes(p.pluginId));

  return (
    <div className="formField">
      <div className="formField__labelRow">
        <label htmlFor="runPlugins">Plugins</label>
        <InfoTip
          label="About run plugins"
          example={
            <>
              {plugins && plugins.length > 0 && (
                <>{plugins.map((p) => `${p.name}@${p.version}`).join(" · ")}{"  —  "}</>
              )}
              <Link to="/plugins">Manage plugins →</Link>
            </>
          }
        >
          Extra JMeter plugin jars staged onto every worker for this run — one
          version per plugin name in the shared library.
        </InfoTip>
      </div>
      <select
        id="runPlugins"
        className="formSelect"
        value=""
        onChange={(e) => { if (e.target.value) onChange([...value, e.target.value]); }}
        disabled={loading || !!error || available.length === 0}
      >
        <option value="">
          {loading ? "loading…"
            : error ? `error: ${error}`
            : available.length === 0
              ? (plugins && plugins.length > 0 ? "— all library plugins selected —" : "— library is empty —")
              : "— add a plugin —"}
        </option>
        {available.map((p) => (
          <option key={p.pluginId} value={p.pluginId}>
            {p.name}@{p.version} · {formatSize(p.sizeBytes)}
          </option>
        ))}
      </select>
      {value.length > 0 && (
        <div className="runPlugins__chips">
          {value.map((id) => {
            const p = byId.get(id);
            const unknown = unknownIds.includes(id);
            return (
              <span key={id} className={`chip${unknown ? " chip--warn" : ""}`}>
                {p ? `${p.name}@${p.version}`
                  : plugins == null ? `${id.slice(0, 8)}…`
                  : `${id.slice(0, 8)}… (removed from library)`}
                <button
                  type="button"
                  className="chip__remove"
                  onClick={() => onChange(value.filter((v) => v !== id))}
                  aria-label={`remove plugin ${p ? p.name : id}`}
                >×</button>
              </span>
            );
          })}
        </div>
      )}
      {unknownIds.length > 0 && (
        <p className="text--error" role="alert">
          {unknownIds.length === 1
            ? "1 plugin from the template is no longer in the library — excluded from this run."
            : `${unknownIds.length} plugins from the template are no longer in the library — excluded from this run.`}
        </p>
      )}
      <small>Optional — staged in addition to the image's baked-in plugins.</small>
    </div>
  );
}
