/**
 * Standardization sweep (2026-05-13) — shared Grid/List toggle used on
 * every IA list page (`/capacity`, `/documents`, `/templates`,
 * `/automation`). Caller owns the persisted state (typically a
 * `useState` initialised from `readPersistedViewMode(storageKey)`); the
 * toggle is purely presentational.
 *
 * <p>Visual matches the toggle that lived inside `<TemplatesDetailPage>`
 * before the extract — operators already knew that affordance, we're
 * standardising on it everywhere.
 */

export type ListViewMode = "grid" | "list";

export interface ViewModeToggleProps {
  viewMode: ListViewMode;
  onChange: (next: ListViewMode) => void;
  /** Optional aria-label override; default is "View mode". */
  ariaLabel?: string;
}

export function ViewModeToggle({ viewMode, onChange, ariaLabel = "View mode" }: ViewModeToggleProps) {
  return (
    <div className="viewModeToggle" role="tablist" aria-label={ariaLabel}>
      {(["list", "grid"] as const).map((mode) => (
        <button
          key={mode}
          type="button"
          role="tab"
          aria-selected={viewMode === mode}
          className={`btn ${viewMode === mode ? "btn--primary" : "btn--ghost"}`}
          onClick={() => onChange(mode)}
        >
          {mode === "grid" ? "Grid" : "List"}
        </button>
      ))}
    </div>
  );
}

/**
 * Read the persisted view mode from localStorage. Defaults to "list"
 * if missing or storage access fails (private-browsing, quota, etc.).
 * The list page table is the safer default — operators see the most
 * info per row when uncertain.
 */
export function readPersistedViewMode(storageKey: string): ListViewMode {
  try {
    const v = localStorage.getItem(storageKey);
    return v === "grid" ? "grid" : "list";
  } catch { return "list"; }
}

/** Best-effort persist; swallows the same storage failures. */
export function persistViewMode(storageKey: string, mode: ListViewMode): void {
  try { localStorage.setItem(storageKey, mode); } catch { /* ignore */ }
}
