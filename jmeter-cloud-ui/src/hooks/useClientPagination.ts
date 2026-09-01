import { useEffect, useMemo, useState } from "react";

/** Page-size choices offered by the paginator's rows-per-page picker. */
export const LIST_PAGE_SIZE_OPTIONS = [15, 25, 50, 100] as const;

/**
 * Every list opens on the top 15; the picker goes up to 100.
 *
 * <p>15 rather than 10 because the list viewport is a fixed height either way,
 * and at 10 the bottom of it sat empty on an ordinary window — the page size
 * and {@code DataList}'s {@code viewportRows} are the same number for exactly
 * that reason, so a full first page fills the box.
 */
export const DEFAULT_LIST_PAGE_SIZE = 15;

/** ONE stored preference — picking a page size on any list applies to all of them. */
const PAGE_SIZE_STORAGE_KEY = "jmeterCloud.listPageSize";

/** The operator's persisted rows-per-page pick, clamped to the offered options. */
export function readStoredPageSize(): number {
  try {
    const v = Number(localStorage.getItem(PAGE_SIZE_STORAGE_KEY));
    return (LIST_PAGE_SIZE_OPTIONS as readonly number[]).includes(v) ? v : DEFAULT_LIST_PAGE_SIZE;
  } catch { return DEFAULT_LIST_PAGE_SIZE; }
}

export function persistPageSize(next: number): void {
  try { localStorage.setItem(PAGE_SIZE_STORAGE_KEY, String(next)); } catch { /* ignore */ }
}

/**
 * Client-side pagination over an already-fetched, already-filtered/sorted
 * in-memory array. The list pages fetch everything up front and narrow it
 * locally (search + sort), so paging is a pure slice — no API round-trip.
 *
 * <p>Pass {@code resetKey} (e.g. the search string and/or sort key) so the
 * view jumps back to page 1 whenever the underlying filter or ordering
 * changes. The page is additionally clamped to the valid range, so a
 * shrinking result set never leaves the operator stranded on an empty page.
 *
 * <p>The page size is the operator's shared preference
 * ({@link DEFAULT_LIST_PAGE_SIZE}, up to 100 — {@code setPageSize} persists it
 * for every list at once); pass
 * {@code fixedPageSize} to pin a bounded surface (e.g. a modal table) to its
 * own size and leave the shared preference untouched.
 */
export function useClientPagination<T>(
  items: T[],
  resetKey?: unknown,
  fixedPageSize?: number,
) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSizeState] = useState<number>(() => fixedPageSize ?? readStoredPageSize());

  // Reset to the first page when the caller's filter/sort identity changes.
  useEffect(() => {
    setPage(1);
  }, [resetKey]);

  function setPageSize(next: number) {
    setPageSizeState(next);
    setPage(1);
    if (fixedPageSize == null) persistPageSize(next);
  }

  const total = items.length;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(Math.max(1, page), totalPages);

  const pageItems = useMemo(
    () => items.slice((safePage - 1) * pageSize, safePage * pageSize),
    [items, safePage, pageSize],
  );

  return { page: safePage, setPage, pageItems, total, pageSize, setPageSize };
}
