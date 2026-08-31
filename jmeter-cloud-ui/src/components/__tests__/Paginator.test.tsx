import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { Paginator } from "../Paginator";

describe("Paginator", () => {
  it("renders the single-page form (no controls) when total <= pageSize", () => {
    render(<Paginator page={1} pageSize={25} total={10} label="runs" onChange={vi.fn()} />);
    expect(screen.getByText("10 runs")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /previous|next/i })).toBeNull();
  });

  it("renders Prev + Next + 'Page N / M' when total > pageSize", () => {
    render(<Paginator page={1} pageSize={25} total={100} label="runs" onChange={vi.fn()} />);
    expect(screen.getByRole("button", { name: /previous page/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /next page/i })).toBeInTheDocument();
    expect(screen.getByText("Page 1 / 4")).toBeInTheDocument();
  });

  it("disables Prev on the first page; disables Next on the last page", () => {
    const { rerender } = render(
      <Paginator page={1} pageSize={25} total={100} onChange={vi.fn()} />,
    );
    expect(screen.getByRole("button", { name: /previous page/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /next page/i })).not.toBeDisabled();

    rerender(<Paginator page={4} pageSize={25} total={100} onChange={vi.fn()} />);
    expect(screen.getByRole("button", { name: /previous page/i })).not.toBeDisabled();
    expect(screen.getByRole("button", { name: /next page/i })).toBeDisabled();
  });

  it("clicking Next fires onChange(page+1)", () => {
    const onChange = vi.fn();
    render(<Paginator page={2} pageSize={25} total={100} onChange={onChange} />);
    fireEvent.click(screen.getByRole("button", { name: /next page/i }));
    expect(onChange).toHaveBeenCalledWith(3);
  });

  it("clicking Prev fires onChange(page-1)", () => {
    const onChange = vi.fn();
    render(<Paginator page={3} pageSize={25} total={100} onChange={onChange} />);
    fireEvent.click(screen.getByRole("button", { name: /previous page/i }));
    expect(onChange).toHaveBeenCalledWith(2);
  });

  it("renders 'Showing X-Y of Z' for the active page window", () => {
    render(<Paginator page={2} pageSize={25} total={100} label="runs" onChange={vi.fn()} />);
    // Match across the bolded segments by reading the parent text.
    const count = document.querySelector('.paginator__count');
    expect(count).toHaveTextContent("Showing 26–50 of 100 runs");
  });

  it("singularizes the count label at exactly one item", () => {
    render(<Paginator page={1} pageSize={10} total={1} label="applications" onChange={vi.fn()} />);
    expect(screen.getByText("1 application")).toBeInTheDocument();
  });

  it("renders the rows-per-page picker only when onPageSizeChange is given, and fires it", () => {
    const onPageSizeChange = vi.fn();
    const { rerender } = render(
      <Paginator page={1} pageSize={10} total={30} label="runs" onChange={vi.fn()} onPageSizeChange={onPageSizeChange} />,
    );
    fireEvent.change(screen.getByLabelText("rows per page"), { target: { value: "50" } });
    expect(onPageSizeChange).toHaveBeenCalledWith(50);
    rerender(<Paginator page={1} pageSize={10} total={30} label="runs" onChange={vi.fn()} />);
    expect(screen.queryByLabelText("rows per page")).toBeNull();
  });

  it("clamps page out-of-range upwards", () => {
    // page=99 against 4-page total → should still render & treat as last page
    render(<Paginator page={99} pageSize={25} total={100} onChange={vi.fn()} />);
    expect(screen.getByText("Page 4 / 4")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /next page/i })).toBeDisabled();
  });
});
