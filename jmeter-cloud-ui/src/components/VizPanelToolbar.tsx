/**
 * Top-of-viz toolbar.
 *
 * <p>The Flow / Form toggle was removed: the
 * launcher now renders both the form (operator edits via the table) and
 * the read-only flow diagram (visual mirror) on the same page. Only the
 * Hide Controls toggle remains. {@code VizViewMode} + the toggle props
 * stay on the type for backward-compat with persisted localStorage
 * values and the parent's passthrough — neither has any UI effect now.
 */

export type VizViewMode = "flow" | "form";

export interface VizPanelToolbarProps {
  controlsHidden: boolean;
  onToggleControls: () => void;
  /** Retained for back-compat; UI no longer toggles between Flow and Form. */
  viewMode?: VizViewMode;
  /** Retained for back-compat; UI no longer toggles between Flow and Form. */
  onViewModeChange?: (mode: VizViewMode) => void;
}

export function VizPanelToolbar({
  controlsHidden, onToggleControls,
}: VizPanelToolbarProps) {
  return (
    <div className="vizPanelToolbar" role="toolbar" aria-label="Visualization panel controls">
      <button
        type="button"
        className="btn btn--ghost"
        onClick={onToggleControls}
        title={controlsHidden ? "Show form pane (keyboard: [)" : "Hide form pane (keyboard: [)"}
        aria-pressed={controlsHidden}
      >
        {controlsHidden ? "▸ Show Controls" : "◂ Hide Controls"}
      </button>
    </div>
  );
}
