import { useEffect, useRef } from "react";

/**
 * Standardization sweep (2026-05-13) — shared filter row for the five
 * list pages (Applications + 4 IA tabs). Holds JUST the search input +
 * "X of Y applications" counter. The view-mode toggle moved to the page
 * header right side (operator feedback) so this toolbar stays small
 * and focused on data narrowing.
 *
 * <p>Caller owns the search string. The hook binds the global `/`
 * keystroke to focus the search input as long as the user isn't
 * already typing in another input/textarea.
 */

export interface AppListToolbarProps {
  search: string;
  onSearchChange: (next: string) => void;
  /** Filtered count (post search). */
  count: number;
  /** Total count (pre search). Used to render "X of Y applications". */
  total: number;
  loading?: boolean;
}

export function AppListToolbar({
  search, onSearchChange, count, total, loading = false,
}: AppListToolbarProps) {
  const searchRef = useRef<HTMLInputElement | null>(null);

  // Page-level rule #11 — '/' focuses search, skipped while typing.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== "/") return;
      const t = e.target as HTMLElement | null;
      const tag = t?.tagName?.toLowerCase();
      if (tag === "input" || tag === "textarea" || (t?.isContentEditable ?? false)) return;
      e.preventDefault();
      searchRef.current?.focus();
      searchRef.current?.select();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <div className="appListToolbar">
      <input
        ref={searchRef}
        type="search"
        placeholder="Filter by application name…"
        value={search}
        onChange={(e) => onSearchChange(e.target.value)}
        className="appListToolbar__search"
        aria-label="Filter applications by name"
        disabled={loading}
      />
      <small className="ink-soft appListToolbar__count">
        {loading ? "—" : `${count} of ${total} application${total === 1 ? "" : "s"}`}
      </small>
    </div>
  );
}
