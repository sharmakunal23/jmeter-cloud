import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";

import { Paginator } from "./Paginator";
import { useClientPagination } from "../hooks/useClientPagination";

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
 * <p><b>It opens on the newest 10.</b> Page size is the operator's shared
 * preference (10 / 25 / 50 / 100, persisted across every list by
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
  /** Rendered in the toolbar beside the bulk actions (a search box, a filter). */
  toolbar?: ReactNode;
  /** Rows visible at once before the body scrolls. Default 10 — the default page size. */
  viewportRows?: number;
}

/** Row height in px; mirrors `--dataList-row-height` in styles.css. */
const ROW_HEIGHT = 44;
const HEADER_HEIGHT = 40;

export function DataList<T>({
  label, columns, rows, rowKey, itemNoun = "items", empty, loading = false,
  resetKey, rowProps, bulkActions, toolbar, viewportRows = 10,
}: DataListProps<T>) {
  const { page, setPage, pageItems, total, pageSize, setPageSize } =
    useClientPagination(rows, resetKey);

  const [selectedIds, setSelectedIds] = useState<ReadonlySet<string>>(new Set());
  const selectable = (bulkActions?.length ?? 0) > 0;

  // Drop selections whose row is gone — a poll that removes a row must not
  // leave it selected and silently included in the next bulk action.
  const presentIds = useMemo(() => new Set(rows.map(rowKey)), [rows, rowKey]);
  useEffect(() => {
    setSelectedIds((prev) => {
      const next = new Set([...prev].filter((id) => presentIds.has(id)));
      return next.size === prev.size ? prev : next;
    });
  }, [presentIds]);

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
          {selectable && selectedRows.length > 0 && (
            <div className="dataList__bulk" role="group" aria-label={`${label} bulk actions`}>
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
                      onClick={() => setSelectedIds(new Set())}>
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
                : pageItems.map((row) => {
                    const id = rowKey(row);
                    const extra = rowProps?.(row) ?? {};
                    return (
                      <tr key={id} {...extra}>
                        {selectable && (
                          <td className="dataList__checkCell"
                              onClick={(e) => e.stopPropagation()}>
                            <input
                              type="checkbox"
                              checked={selectedIds.has(id)}
                              onChange={() => toggleRow(id)}
                              aria-label={`Select row ${id}`}
                            />
                          </td>
                        )}
                        {columns.map((c) => (
                          <td key={c.key} className={c.className}>{c.cell(row)}</td>
                        ))}
                      </tr>
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
