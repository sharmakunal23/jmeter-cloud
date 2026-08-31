import { LIST_PAGE_SIZE_OPTIONS } from "../hooks/useClientPagination";

/**
 * URL- or state-driven paginator, rendered as a sticky bar at the bottom of
 * the viewport (CSS {@code position: sticky}) so the count and controls never
 * drift down as a list grows. {@code page} is 1-based for human readability.
 *
 * <p>Stateless on its own — {@code onChange} fires when the operator picks a
 * page, and {@code onPageSizeChange} (when provided) offers the shared
 * rows-per-page picker (10 default / 25 / 50 / 100); the parent persists both.
 */

export interface PaginatorProps {
  /** 1-based current page. */
  page: number;
  pageSize: number;
  /** Total rows across all pages (drives the page count). */
  total: number;
  /** Optional label for the visible count copy, plural ("runs"); auto-singularized at total 1. */
  label?: string;
  onChange: (nextPage: number) => void;
  /** When provided, the bar renders the shared rows-per-page picker. */
  onPageSizeChange?: (nextSize: number) => void;
}

export function Paginator({
  page, pageSize, total, label = "items", onChange, onPageSizeChange,
}: PaginatorProps) {
  const noun = total === 1 && label.endsWith("s") ? label.slice(0, -1) : label;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.max(1, Math.min(page, totalPages));
  const firstShown = (safePage - 1) * pageSize + 1;
  const lastShown = Math.min(safePage * pageSize, total);
  const hasPrev = safePage > 1;
  const hasNext = safePage < totalPages;

  const sizePicker = onPageSizeChange != null && (
    <label className="paginator__size">
      Show
      <select
        className="formSelect paginator__sizeSelect"
        value={pageSize}
        onChange={(e) => onPageSizeChange(Number(e.target.value))}
        aria-label="rows per page"
      >
        {LIST_PAGE_SIZE_OPTIONS.map((n) => (
          <option key={n} value={n}>{n}</option>
        ))}
      </select>
      per page
    </label>
  );

  if (total <= pageSize) {
    // Single-page result — the count (plus the picker) for orientation.
    return (
      <nav className="paginator paginator--single" aria-label="pagination">
        <span className="paginator__count">
          {total} {noun}
        </span>
        {sizePicker}
      </nav>
    );
  }

  return (
    <nav className="paginator" aria-label="pagination">
      <span className="paginator__count">
        Showing <strong>{firstShown}</strong>–<strong>{lastShown}</strong>{" "}
        of <strong>{total}</strong> {noun}
      </span>
      <span className="paginator__controls">
        {sizePicker}
        <button
          type="button"
          className="btn btn--ghost"
          disabled={!hasPrev}
          onClick={() => onChange(safePage - 1)}
          aria-label="previous page"
        >
          ← Prev
        </button>
        <span className="paginator__pageOf" aria-live="polite">
          Page {safePage} / {totalPages}
        </span>
        <button
          type="button"
          className="btn btn--ghost"
          disabled={!hasNext}
          onClick={() => onChange(safePage + 1)}
          aria-label="next page"
        >
          Next →
        </button>
      </span>
    </nav>
  );
}
