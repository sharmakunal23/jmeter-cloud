import React from "react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, within } from "@testing-library/react";

import { DataList } from "../DataList";
import { DEFAULT_LIST_PAGE_SIZE, LIST_PAGE_SIZE_OPTIONS } from "../../hooks/useClientPagination";

interface Row { id: string; name: string }

function rows(n: number): Row[] {
  return Array.from({ length: n }, (_, i) => ({ id: `r${i}`, name: `row ${i}` }));
}

const columns = [{ key: "name", header: "Name", cell: (r: Row) => r.name }];

function renderList(props: Partial<React.ComponentProps<typeof DataList<Row>>> = {}) {
  return render(
    <DataList<Row>
      label="things"
      columns={columns}
      rows={rows(30)}
      rowKey={(r) => r.id}
      empty={<>nothing here</>}
      {...props}
    />,
  );
}

beforeEach(() => {
  try { localStorage.clear(); } catch { /* ignore */ }
});

describe("DataList — the one list shape", () => {
  it("opens on the top 10 and offers 10 / 25 / 50 / 100", () => {
    renderList();

    expect(screen.getAllByRole("row")).toHaveLength(DEFAULT_LIST_PAGE_SIZE + 1); // + header
    expect(screen.getByText("row 0")).toBeInTheDocument();
    expect(screen.queryByText("row 10")).not.toBeInTheDocument();

    const picker = screen.getByLabelText("rows per page") as HTMLSelectElement;
    expect([...picker.options].map((o) => Number(o.value))).toEqual([...LIST_PAGE_SIZE_OPTIONS]);
    expect(LIST_PAGE_SIZE_OPTIONS[LIST_PAGE_SIZE_OPTIONS.length - 1]).toBe(100);
  });

  it("the page size is a shared preference — picking one persists it for every list", () => {
    const { unmount } = renderList();
    fireEvent.change(screen.getByLabelText("rows per page"), { target: { value: "25" } });
    expect(screen.getAllByRole("row")).toHaveLength(26);
    unmount();

    renderList();
    expect((screen.getByLabelText("rows per page") as HTMLSelectElement).value).toBe("25");
  });

  it("the viewport height does NOT change with the number of rows — the whole point", () => {
    const { container: few } = render(
      <DataList<Row> label="t" columns={columns} rows={rows(2)} rowKey={(r) => r.id} empty={<>none</>} />);
    const shortHeight = (few.querySelector(".dataList__viewport") as HTMLElement).style.height;

    const { container: many } = render(
      <DataList<Row> label="t" columns={columns} rows={rows(500)} rowKey={(r) => r.id} empty={<>none</>} />);
    const tallHeight = (many.querySelector(".dataList__viewport") as HTMLElement).style.height;

    expect(shortHeight).toBe(tallHeight);
    expect(shortHeight).not.toBe("");
  });

  it("the height is independent of the PAGE SIZE too — picking 100 must not make a 100-row box", () => {
    const { container } = renderList();
    const before = (container.querySelector(".dataList__viewport") as HTMLElement).style.height;

    fireEvent.change(screen.getByLabelText("rows per page"), { target: { value: "100" } });

    const after = (container.querySelector(".dataList__viewport") as HTMLElement).style.height;
    expect(after).toBe(before);          // the box is a viewport, not the page
    expect(screen.getAllByRole("row").length).toBeGreaterThan(11);   // ...and it really did page to 100
  });

  it("an empty list is still the same height — nothing below it moves as data arrives", () => {
    const { container } = render(
      <DataList<Row> label="t" columns={columns} rows={[]} rowKey={(r) => r.id} empty={<>nothing here</>} />);

    expect(screen.getByText("nothing here")).toBeInTheDocument();
    expect((container.querySelector(".dataList__viewport") as HTMLElement).style.height).not.toBe("");
  });

  it("no checkbox column unless a bulk action exists", () => {
    renderList();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();

    renderList({ bulkActions: [{ label: "Delete", onRun: () => {} }] });
    expect(screen.getAllByRole("checkbox").length).toBeGreaterThan(0);
  });

  it("selecting rows reveals the bulk bar and hands the action exactly those rows", () => {
    const onRun = vi.fn();
    renderList({ bulkActions: [{ label: "Delete", danger: true, onRun }] });

    const boxes = screen.getAllByRole("checkbox");
    fireEvent.click(boxes[1]!);   // first body row
    fireEvent.click(boxes[2]!);

    expect(screen.getByText("2 selected")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(onRun).toHaveBeenCalledTimes(1);
    expect(onRun.mock.calls[0]![0].map((r: Row) => r.id)).toEqual(["r0", "r1"]);
  });

  it("the header checkbox selects THIS page only — never rows the operator cannot see", () => {
    const onRun = vi.fn();
    renderList({ bulkActions: [{ label: "Delete", onRun }] });

    fireEvent.click(screen.getAllByRole("checkbox")[0]!);   // header
    expect(screen.getByText("10 selected")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(onRun.mock.calls[0]![0]).toHaveLength(10);
  });

  it("a selection survives paging, so a bulk action can span pages deliberately", () => {
    const onRun = vi.fn();
    renderList({ bulkActions: [{ label: "Delete", onRun }] });

    fireEvent.click(screen.getAllByRole("checkbox")[1]!);        // r0 on page 1
    fireEvent.click(screen.getByRole("button", { name: "next page" }));
    fireEvent.click(screen.getAllByRole("checkbox")[1]!);        // r10 on page 2

    expect(screen.getByText("2 selected")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(onRun.mock.calls[0]![0].map((r: Row) => r.id)).toEqual(["r0", "r10"]);
  });

  it("a row that disappears on a poll drops out of the selection rather than being silently acted on", () => {
    const onRun = vi.fn();
    const { rerender } = render(
      <DataList<Row> label="t" columns={columns} rows={rows(3)} rowKey={(r) => r.id}
                     empty={<>none</>} bulkActions={[{ label: "Delete", onRun }]} />);

    fireEvent.click(screen.getAllByRole("checkbox")[1]!);   // r0
    fireEvent.click(screen.getAllByRole("checkbox")[2]!);   // r1
    expect(screen.getByText("2 selected")).toBeInTheDocument();

    // r0 is gone — a concurrent delete, or a poll that no longer lists it.
    rerender(
      <DataList<Row> label="t" columns={columns} rows={rows(3).slice(1)} rowKey={(r) => r.id}
                     empty={<>none</>} bulkActions={[{ label: "Delete", onRun }]} />);

    expect(screen.getByText("1 selected")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(onRun.mock.calls[0]![0].map((r: Row) => r.id)).toEqual(["r1"]);
  });

  it("Clear drops the whole selection", () => {
    renderList({ bulkActions: [{ label: "Delete", onRun: () => {} }] });
    fireEvent.click(screen.getAllByRole("checkbox")[0]!);
    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    expect(screen.queryByText(/selected/)).not.toBeInTheDocument();
  });

  it("a bulk action can refuse a selection it cannot handle", () => {
    renderList({ bulkActions: [{ label: "Enable", onRun: () => {}, disabled: () => true }] });
    fireEvent.click(screen.getAllByRole("checkbox")[1]!);
    expect(screen.getByRole("button", { name: "Enable" })).toBeDisabled();
  });

  it("loading shows skeleton rows, not an empty state that says there is nothing", () => {
    renderList({ rows: [], loading: true });
    expect(screen.queryByText("nothing here")).not.toBeInTheDocument();
    expect(document.querySelectorAll(".skeleton").length).toBeGreaterThan(0);
  });

  it("a sortable header is a button and reports its direction to assistive tech", () => {
    const onSort = vi.fn();
    renderList({
      columns: [{ key: "name", header: "Name", cell: (r: Row) => r.name, onSort, sortDirection: "asc" }],
    });

    const header = screen.getByRole("columnheader", { name: /Name/ });
    expect(header).toHaveAttribute("aria-sort", "ascending");
    fireEvent.click(within(header).getByRole("button"));
    expect(onSort).toHaveBeenCalled();
  });

  it("controlled selection does not re-render forever when nothing changed", () => {
    // The regression: the prune effect runs every render and returns the same
    // set when there is nothing to prune. A controlled parent that stores
    // `new Set(next)` would then be told the selection changed on every
    // render. Guard removed → this test hangs the suite.
    const onSelectionChange = vi.fn();
    function Controlled() {
      const [ids, setIds] = React.useState<ReadonlySet<string>>(new Set(["r0"]));
      return (
        <DataList<Row>
          label="t" columns={columns} rows={rows(5)} rowKey={(r) => r.id}
          empty={<>none</>}
          bulkActions={[{ label: "Delete", onRun: () => {} }]}
          selectedIds={ids}
          onSelectionChange={(next) => { onSelectionChange(next); setIds(new Set(next)); }}
        />
      );
    }
    render(<Controlled />);

    expect(screen.getByText("1 selected")).toBeInTheDocument();
    // A no-op prune must not be reported as a change at all.
    expect(onSelectionChange).not.toHaveBeenCalled();
  });

  it("controlled selection still round-trips a real toggle", () => {
    function Controlled() {
      const [ids, setIds] = React.useState<ReadonlySet<string>>(new Set());
      return (
        <DataList<Row>
          label="t" columns={columns} rows={rows(5)} rowKey={(r) => r.id}
          empty={<>none</>}
          bulkActions={[{ label: "Delete", onRun: () => {} }]}
          selectedIds={ids}
          onSelectionChange={(next) => setIds(new Set(next))}
        />
      );
    }
    render(<Controlled />);

    fireEvent.click(screen.getAllByRole("checkbox")[1]!);
    expect(screen.getByText("1 selected")).toBeInTheDocument();
  });

  it("rowGroup bands consecutive rows, and repeats the heading on a page it spans into", () => {
    const many: Row[] = [
      ...Array.from({ length: 8 }, (_, i) => ({ id: `a${i}`, name: `alpha ${i}` })),
      ...Array.from({ length: 8 }, (_, i) => ({ id: `b${i}`, name: `beta ${i}` })),
    ];
    render(
      <DataList<Row> label="t" columns={columns} rows={many} rowKey={(r) => r.id} empty={<>none</>}
                     rowGroup={(r) => ({ key: r.id[0]!, label: `group ${r.id[0]}` })} />);

    // Page 1: 8 alphas + the first 2 betas — so BOTH headings appear.
    expect(screen.getByText("group a")).toBeInTheDocument();
    expect(screen.getByText("group b")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "next page" }));
    // Page 2 is all betas — the heading repeats rather than leaving the page
    // opening on rows whose group is off-screen above.
    expect(screen.getByText("group b")).toBeInTheDocument();
    expect(screen.queryByText("group a")).not.toBeInTheDocument();
  });

  it("server pagination renders the given page as-is and never pages it again", () => {
    const onPageChange = vi.fn();
    render(
      <DataList<Row> label="t" columns={columns} rows={rows(25)} rowKey={(r) => r.id}
                     empty={<>none</>}
                     pagination={{ page: 2, pageSize: 25, total: 300,
                                   onPageChange, onPageSizeChange: () => {} }} />);

    // All 25 handed in are shown — the list must not slice a page it was given.
    expect(screen.getAllByRole("row")).toHaveLength(26);
    expect(screen.getByRole("navigation", { name: "pagination" })).toHaveTextContent("300");

    fireEvent.click(screen.getByRole("button", { name: "next page" }));
    expect(onPageChange).toHaveBeenCalledWith(3);
  });

  it("server pagination does NOT prune a selection made on another page", () => {
    const onSelectionChange = vi.fn();
    // "r99" is selected but is not on this page — pruning it would silently
    // drop what the operator picked before paging.
    render(
      <DataList<Row> label="t" columns={columns} rows={rows(3)} rowKey={(r) => r.id}
                     empty={<>none</>}
                     bulkActions={[{ label: "Delete", onRun: () => {} }]}
                     selectedIds={new Set(["r99"])}
                     onSelectionChange={onSelectionChange}
                     pagination={{ page: 2, pageSize: 3, total: 300,
                                   onPageChange: () => {}, onPageSizeChange: () => {} }} />);

    expect(onSelectionChange).not.toHaveBeenCalled();
  });

  it("a row's checkbox says what the row IS, not its id", () => {
    renderList({
      bulkActions: [{ label: "Delete", onRun: () => {} }],
      rowSelectionLabel: (r: Row) => `Select ${r.name}`,
    });
    expect(screen.getByRole("checkbox", { name: "Select row 0" })).toBeInTheDocument();
  });
});
