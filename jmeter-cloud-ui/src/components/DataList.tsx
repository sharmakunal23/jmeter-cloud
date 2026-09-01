import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";

import { Paginator } from "./Paginator";
import { DEFAULT_LIST_PAGE_SIZE, useClientPagination } from "../hooks/useClientPagination";

/**
 * The one list in this app. Every table an operator scans — runs, documents,
 * plugins, schedules, applications — is this component with different columns,
 * so the paging, the row height, the selection and the keyboard behaviour are
 * learned once.
 *
 * <p>Three properties it exists to guarantee:
 *
 * <p><b>The height never changes.</b> The body is a fixed viewport that scrolls
 * internally, so a list of 3 rows and a list of 100 occupy the same space and
 * nothing below them moves as data arrives. A page whose layout reflows on
 * every poll is the thing this replaces.
 *
 * <p><b>It opens on the newest 15.</b> Page size is the operator's shared
 * preference (15 / 25 / 50 / 100, persisted across every list by
 * {@code useClientPagination}), and callers hand rows in already sorted —
 * newest first is the caller's job, because only it knows which column means
 * "recent".
 *
 * <p><b>Selection is opt-in and only where a bulk action exists.</b> A checkbox
 * column on a list you cannot bulk-operate is a control that does nothing.
 * Selection survives paging (it is keyed by row id, not index) and clears when
 * the underlying rows change identity.
 */

export interface DataListColumn<T> {
  /** Stable id; also the header's React key. */
  key: string;
  header: ReactNode;
  cell: (row: T) => ReactNode;
  /** Extra class on the header cell and every body cell in this column. */
  className?: string;
  /** When set, the header is a sort button and this fires with the column key. */
  onSort?: () => void;
  /** Rendered in the header when this column is the sort key. */
  sortDirection?: "asc" | "desc" | null;
}

export interface DataListBulkAction<T> {
  label: string;
  /** Rendered in a danger style and confirmed by the caller. */
  danger?: boolean;
  onRun: (selected: T[]) => void;
  /** Disable for a selection this action cannot handle. */
  disabled?: (selected: T[]) => boolean;
}

export interface DataListProps<T> {
  /** Accessible name for the table. */
  label: string;
  columns: DataListColumn<T>[];
  /** Already filtered and sorted — newest first is the caller's decision. */
  rows: T[];
  rowKey: (row: T) => string;
  /**
   * Accessible name for a row's checkbox. Default is the row key, which is an
   * id — say what the row IS instead wherever you can, because "select row
   * 01ARJ…" tells a screen-reader user nothing about what they are about to
   * delete.
   */
  rowSelectionLabel?: (row: T) => string;
  /** Noun for the paginator's count copy, plural. */
  itemNoun?: string;
  /** Shown in the body when there are no rows at all. */
  empty: ReactNode;
  /** True while the first load is in flight — renders skeleton rows, not an empty state. */
  loading?: boolean;
  /** Changing this returns to page 1 (pass the search term + sort key). */
  resetKey?: unknown;
  /** Extra class on the row, e.g. for a clickable row. */
  rowProps?: (row: T) => React.HTMLAttributes<HTMLTableRowElement>;
  /** Opt in to the checkbox column by supplying at least one bulk action. */
  bulkActions?: DataListBulkAction<T>[];
  /**
   * Lift the selection when the page needs it too — a toolbar button outside
   * the list, a dialog that takes the selected rows. Omit both and the list
   * owns its own selection, which is what most callers want.
   */
  selectedIds?: ReadonlySet<string>;
  onSelectionChange?: (next: ReadonlySet<string>) => void;
  /** Rendered in the toolbar beside the bulk actions (a search box, a filter). */
  toolbar?: ReactNode;
  /**
   * Rows visible at once before the body scrolls. Defaults to the default page
   * size, so a full first page fills the box exactly and a larger page size
   * scrolls inside the same height rather than growing the page.
   */
  viewportRows?: number;
  /**
   * Groups consecutive rows under a heading row — the applications list bands
   * its apps by application group. Rows must already be sorted so a group's
   * rows are adjacent; the heading renders whenever the key changes, including
   * at the top of a page a group spans into, so a page never opens on rows
   * whose group is off-screen above.
   */
  rowGroup?: (row: T) => { key: string; label: ReactNode };
  /**
   * Server-paginated mode: `rows` is ONE page the caller already fetched, and
   * the caller owns page/size. Without this the list pages the array it is
   * given, which is what almost every caller wants.
   *
   * <p>In this mode the list does <b>not</b> prune selections for rows it
   * cannot see — the other pages are not in `rows`, so pruning would silently
   * drop everything the operator selected before paging.
   */
  pagination?: {
    page: number;
    pageSize: number;
    total: number;
    onPageChange: (page: number) => void;
    onPageSizeChange: (size: number) => void;
  };
}

/** Row height in px; mirrors `--dataList-row-height` in styles.css. */
const ROW_HEIGHT = 44;
const HEADER_HEIGHT = 40;

export function DataList<T>({
  label, columns, rows, rowKey, itemNoun = "items", empty, loading = false,
  resetKey, rowProps, bulkActions, toolbar, viewportRows = DEFAULT_LIST_PAGE_SIZE, rowSelectionLabel,
  selectedIds: controlledIds, onSelectionChange, rowGroup, pagination,
}: DataListProps<T>) {
  // Always called (hooks cannot be conditional); ignored in server mode, where
  // paging a single already-fetched page would be wrong.
  const client = useClientPagination(rows, resetKey);
  const serverPaged = pagination != null;
  const page = serverPaged ? pagination.page : client.page;
  const pageSize = serverPaged ? pagination.pageSize : client.pageSize;
  const total = serverPaged ? pagination.total : client.total;
  const pageItems = serverPaged ? rows : client.pageItems;
  const setPage = serverPaged ? pagination.onPageChange : client.setPage;
  const setPageSize = serverPaged ? pagination.onPageSizeChange : client.setPageSize;

  const [ownIds, setOwnIds] = useState<ReadonlySet<string>>(new Set());
  const controlled = controlledIds !== undefined;
  const selectedIds = controlled ? controlledIds : ownIds;
  const setSelectedIds = useCallback((update: (prev: ReadonlySet<string>) => ReadonlySet<string>) => {
    if (controlled) {
      // The identity guard is load-bearing. The prune effect below runs on
      // every render (its `rows`/`rowKey` deps are new objects each time) and
      // returns the SAME set when there is nothing to prune. Without this,
      // a controlled parent storing `new Set(next)` would be told the
      // selection "changed" on every render and re-render forever.
      const next = update(controlledIds!);
      if (next !== controlledIds) onSelectionChange?.(next);
    } else {
      setOwnIds(update);
    }
  }, [controlled, controlledIds, onSelectionChange]);
  const selectable = (bulkActions?.length ?? 0) > 0 || controlled;

  // Drop selections whose row is gone — a poll that removes a row must not
  // leave it selected and silently included in the next bulk action. Skipped
  // in server mode: `rows` is one page, so the rows a selection spans are
  // simply not here to find.
  const presentIds = useMemo(() => new Set(rows.map(rowKey)), [rows, rowKey]);
  useEffect(() => {
    if (serverPaged) return;
    setSelectedIds((prev) => {
      const next = new Set([...prev].filter((id) => presentIds.has(id)));
      return next.size === prev.size ? prev : next;
    });
  }, [presentIds, serverPaged]);

  const selectedRows = useMemo(
    () => rows.filter((r) => selectedIds.has(rowKey(r))),
    [rows, selectedIds, rowKey],
  );

  const pageIds = pageItems.map(rowKey);
  const allOnPageSelected = pageIds.length > 0 && pageIds.every((id) => selectedIds.has(id));
  const someOnPageSelected = pageIds.some((id) => selectedIds.has(id));

  const headerCheckbox = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (headerCheckbox.current) {
      headerCheckbox.current.indeterminate = someOnPageSelected && !allOnPageSelected;
    }
  }, [someOnPageSelected, allOnPageSelected]);

  function toggleRow(id: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }

  /** The header checkbox acts on the visible page only — never on rows off-screen. */
  function togglePage() {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (allOnPageSelected) pageIds.forEach((id) => next.delete(id));
      else pageIds.forEach((id) => next.add(id));
      return next;
    });
  }

  const colCount = columns.length + (selectable ? 1 : 0);
  const bodyHeight = viewportRows * ROW_HEIGHT;

  return (
    <div className="dataList">
      {(toolbar || selectable) && (
        <div className="dataList__toolbar">
          {toolbar}
          {(bulkActions?.length ?? 0) > 0 && selectedRows.length > 0 && (
            <div className="dataList__bulk" role="toolbar" aria-label={`${label} bulk actions`}>
              <span className="dataList__bulkCount">{selectedRows.length} selected</span>
              {bulkActions!.map((a) => (
                <button
                  key={a.label}
                  type="button"
                  className={`btn btn--sm ${a.danger ? "btn--ghost text--error" : "btn--ghost"}`}
                  disabled={a.disabled?.(selectedRows) ?? false}
                  onClick={() => a.onRun(selectedRows)}
                >
                  {a.label}
                </button>
              ))}
              <button type="button" className="btn btn--ghost btn--sm"
                      onClick={() => setSelectedIds(() => new Set<string>())}>
                Clear
              </button>
            </div>
          )}
        </div>
      )}

      <div
        className="dataList__viewport"
        style={{ height: bodyHeight + HEADER_HEIGHT }}
      >
        <table className="dataList__table" aria-label={label}>
          <thead>
            <tr>
              {selectable && (
                <th className="dataList__checkCell">
                  <input
                    ref={headerCheckbox}
                    type="checkbox"
                    checked={allOnPageSelected}
                    onChange={togglePage}
                    aria-label={`Select all ${label} on this page`}
                  />
                </th>
              )}
              {columns.map((c) => (
                <th key={c.key} className={c.className}
                    aria-sort={c.sortDirection ? (c.sortDirection === "asc" ? "ascending" : "descending") : undefined}>
                  {c.onSort ? (
                    <button type="button" className="dataList__sort" onClick={c.onSort}>
                      {c.header}
                      {c.sortDirection && <span aria-hidden="true">{c.sortDirection === "asc" ? " ▲" : " ▼"}</span>}
                    </button>
                  ) : c.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {loading
              ? Array.from({ length: Math.min(viewportRows, 5) }, (_, i) => (
                  <tr key={`skeleton-${i}`} aria-hidden="true">
                    {Array.from({ length: colCount }, (__, j) => (
                      <td key={j}><span className="skeleton skeleton--text" /></td>
                    ))}
                  </tr>
                ))
              : pageItems.length === 0
                ? (
                  <tr className="dataList__emptyRow">
                    <td colSpan={colCount}>{empty}</td>
                  </tr>
                )
                : pageItems.map((row, i) => {
                    const id = rowKey(row);
                    const extra = rowProps?.(row) ?? {};
                    const group = rowGroup?.(row);
                    // A heading whenever the group changes — and at index 0, so
                    // a page a group spans into still says which group it is.
                    const opensGroup = group != null
                      && (i === 0 || rowGroup!(pageItems[i - 1]!).key !== group.key);
                    return (
                      <Fragment key={id}>
                      {opensGroup && (
                        <tr className="dataList__groupRow">
                          <td colSpan={colCount}>{group!.label}</td>
                        </tr>
                      )}
                      <tr {...extra}>
                        {selectable && (
                          <td className="dataList__checkCell"
                              onClick={(e) => e.stopPropagation()}>
                            <input
                              type="checkbox"
                              checked={selectedIds.has(id)}
                              onChange={() => toggleRow(id)}
                              aria-label={rowSelectionLabel?.(row) ?? `Select row ${id}`}
                            />
                          </td>
                        )}
                        {columns.map((c) => (
                          <td key={c.key} className={c.className}>{c.cell(row)}</td>
                        ))}
                      </tr>
                      </Fragment>
                    );
                  })}
          </tbody>
        </table>
      </div>

      <Paginator
        page={page}
        pageSize={pageSize}
        total={total}
        label={itemNoun}
        onChange={setPage}
        onPageSizeChange={setPageSize}
      />
    </div>
  );
}
