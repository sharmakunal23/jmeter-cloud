import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { RegionPicker } from "../RegionPicker";

function rowFor(container: HTMLElement, regionId: string): HTMLElement {
  const row = [...container.querySelectorAll<HTMLElement>(".regionChecklist__row")]
    .find((el) => el.textContent?.includes(regionId));
  if (!row) throw new Error(`no checklist row for ${regionId}`);
  return row;
}

describe("RegionPicker", () => {
  it("renders the 4 USA region checkboxes + map pins and pre-selects current", () => {
    const { container } = render(
      <RegionPicker appName="checkout-svc" current={["us-east-1"]} onSubmit={vi.fn()} onCancel={vi.fn()} />,
    );
    const inputs = container.querySelectorAll<HTMLInputElement>('.regionChecklist input[type="checkbox"]');
    expect(inputs).toHaveLength(4);
    expect([...inputs].filter((i) => i.checked)).toHaveLength(1);
    expect(container.querySelectorAll(".regionPin")).toHaveLength(4);
  });

  it("toggling a region on and saving submits the new selection", () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RegionPicker appName="x" current={["us-east-1"]} onSubmit={onSubmit} onCancel={vi.fn()} />,
    );
    fireEvent.click(rowFor(container, "us-west-2").querySelector('input[type="checkbox"]')!);
    fireEvent.click(screen.getByRole("button", { name: /Save regions/ }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
    const arg = onSubmit.mock.calls[0][0] as string[];
    expect(arg).toEqual(expect.arrayContaining(["us-east-1", "us-west-2"]));
  });

  it("a locked region (has workers) cannot be deselected", () => {
    const { container } = render(
      <RegionPicker
        appName="x"
        current={["us-east-1"]}
        lockedRegions={new Set(["us-east-1"])}
        onSubmit={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    const input = rowFor(container, "us-east-1").querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    expect(input.disabled).toBe(true);
  });

  it("Save is disabled with no changes", () => {
    render(<RegionPicker appName="x" current={["us-east-1"]} onSubmit={vi.fn()} onCancel={vi.fn()} />);
    expect(screen.getByRole("button", { name: /Save regions/ })).toBeDisabled();
  });
});
