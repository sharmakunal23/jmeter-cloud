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
  /** What the list holds, singular; drives the placeholder, the aria-label and the count. */
  noun?: ListNoun;
}

/** The nouns a list can hold. Adding one means adding its row to {@link FILTER_TEXT}. */
export type ListNoun =
  | "application" | "group" | "plugin" | "cluster" | "workflow" | "schedule";

/** Placeholder + aria-label per noun, so every list's filter reads the same way. */
const FILTER_TEXT: Record<ListNoun, { placeholder: string; label: string }> = {
  application: { placeholder: "Filter by application name…", label: "Filter applications by name" },
  group:       { placeholder: "Filter by group name or id…", label: "Filter groups by name or id" },
  plugin:      { placeholder: "Filter by plugin name…", label: "Filter plugins by name" },
  cluster:     { placeholder: "Filter by cluster name, id or endpoint…", label: "Filter clusters" },
  workflow:    { placeholder: "Filter by workflow name…", label: "Filter workflows by name" },
  schedule:    { placeholder: "Filter by schedule name…", label: "Filter schedules by name" },
};

export function AppListToolbar({
  search, onSearchChange, count, total, loading = false, noun = "application",
}: AppListToolbarProps) {
  const { placeholder, label } = FILTER_TEXT[noun];
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
        placeholder={placeholder}
        value={search}
        onChange={(e) => onSearchChange(e.target.value)}
        className="appListToolbar__search"
        aria-label={label}
        disabled={loading}
      />
      <small className="ink-soft appListToolbar__count">
        {loading ? "—" : `${count} of ${total} ${noun}${total === 1 ? "" : "s"}`}
      </small>
    </div>
  );
}
