import { useEffect, useMemo, useState } from "react";

/**
 * Default page size for the nav-tab list pages (Applications, Capacity,
 * Documents, Templates, Automation). Show the top 15 rows, then paginate —
 * past that point the operator would have to scroll a long table to see more.
 */
export const LIST_PAGE_SIZE = 15;

/**
 * Client-side pagination over an already-fetched, already-filtered/sorted
 * in-memory array. The list pages fetch everything up front and narrow it
 * locally (search + sort), so paging is a pure slice — no API round-trip.
 *
 * <p>Pass {@code resetKey} (e.g. the search string and/or sort key) so the
 * view jumps back to page 1 whenever the underlying filter or ordering
 * changes. The page is additionally clamped to the valid range, so a
 * shrinking result set (e.g. a narrowing search while on a later page)
 * never leaves the operator stranded on an empty page.
 *
 * @returns the clamped 1-based {@code page}, a {@code setPage} setter wired
 *   straight to {@code <Paginator onChange>}, the {@code pageItems} slice to
 *   render, and {@code total} / {@code pageSize} for the paginator props.
 */
export function useClientPagination<T>(
  items: T[],
  resetKey?: unknown,
  pageSize: number = LIST_PAGE_SIZE,
) {
  const [page, setPage] = useState(1);

  // Reset to the first page when the caller's filter/sort identity changes.
  useEffect(() => {
    setPage(1);
  }, [resetKey]);

  const total = items.length;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(Math.max(1, page), totalPages);

  const pageItems = useMemo(
    () => items.slice((safePage - 1) * pageSize, safePage * pageSize),
    [items, safePage, pageSize],
  );

  return { page: safePage, setPage, pageItems, total, pageSize };
}
