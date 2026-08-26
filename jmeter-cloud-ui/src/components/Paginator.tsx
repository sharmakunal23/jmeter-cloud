/**
 * URL-driven paginator. Drives the Applications-detail runs
 * list and is intended to be reused by any other table that needs
 * page navigation. {@code page} is 1-based for human readability.
 *
 * <p>Stateless on its own — {@code onChange} fires when the operator
 * picks a page; the parent persists the chosen page (e.g. as a
 * {@code ?page=N} search param via React Router).
 */

export interface PaginatorProps {
  /** 1-based current page. */
  page: number;
  pageSize: number;
  /** Total rows across all pages (drives the page count). */
  total: number;
  /** Optional label prefix for the visible "showing X-Y of Z" copy. */
  label?: string;
  onChange: (nextPage: number) => void;
}

export function Paginator({ page, pageSize, total, label = "items", onChange }: PaginatorProps) {
  if (total <= pageSize) {
    // Single-page result — no controls, just the count for orientation.
    return (
      <div className="paginator paginator--single" aria-label="pagination">
        <span className="paginator__count">
          {total} {label}
        </span>
      </div>
    );
  }

  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.max(1, Math.min(page, totalPages));
  const firstShown = (safePage - 1) * pageSize + 1;
  const lastShown = Math.min(safePage * pageSize, total);
  const hasPrev = safePage > 1;
  const hasNext = safePage < totalPages;

  return (
    <nav className="paginator" aria-label="pagination">
      <span className="paginator__count">
        Showing <strong>{firstShown}</strong>–<strong>{lastShown}</strong>{" "}
        of <strong>{total}</strong> {label}
      </span>
      <span className="paginator__controls">
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
