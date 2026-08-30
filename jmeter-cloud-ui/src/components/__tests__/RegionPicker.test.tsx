import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { RegionPicker } from "../RegionPicker";
import { __resetPlatformCapabilitiesCache } from "../../hooks/usePlatformCapabilities";

function rowFor(container: HTMLElement, regionId: string): HTMLElement {
  const row = [...container.querySelectorAll<HTMLElement>(".regionChecklist__row")]
    .find((el) => el.textContent?.includes(regionId));
  if (!row) throw new Error(`no checklist row for ${regionId}`);
  return row;
}

describe("RegionPicker", () => {
  it("renders the 4 USA region checkboxes + map pins and pre-selects current", () => {
    const { container } = render(
      <RegionPicker groupName="checkout-svc" current={["us-east-1"]} onSubmit={vi.fn()} onCancel={vi.fn()} />,
    );
    const inputs = container.querySelectorAll<HTMLInputElement>('.regionChecklist input[type="checkbox"]');
    expect(inputs).toHaveLength(4);
    expect([...inputs].filter((i) => i.checked)).toHaveLength(1);
    expect(container.querySelectorAll(".regionPin")).toHaveLength(4);
  });

  it("toggling a region on and saving submits the new selection", () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RegionPicker groupName="x" current={["us-east-1"]} onSubmit={onSubmit} onCancel={vi.fn()} />,
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
        groupName="x"
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
    render(<RegionPicker groupName="x" current={["us-east-1"]} onSubmit={vi.fn()} onCancel={vi.fn()} />);
    expect(screen.getByRole("button", { name: /Save regions/ })).toBeDisabled();
  });

  // ── STATIC-FLEET Phase 7 — deployment-supplied placement options ──────

  describe("on a private cloud with named data centers", () => {
    beforeEach(() => {
      __resetPlatformCapabilitiesCache();
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({
          provisioningMode: "STATIC",
          dynamicScalingEnabled: false,
          podRecyclingEnabled: false,
          regions: ["na-east", "na-west", "eu-central"],
          regionLabel: "dataCenter",
        }),
      }));
    });
    afterEach(() => {
      vi.unstubAllGlobals();
      __resetPlatformCapabilitiesCache();
    });

    it("offers the deployment's data centers instead of the four AWS regions", async () => {
      const { container } = render(
        <RegionPicker groupName="x" current={["na-east"]} onSubmit={vi.fn()} onCancel={vi.fn()} />,
      );

      await waitFor(() => {
        const inputs = container.querySelectorAll('.regionChecklist input[type="checkbox"]');
        expect(inputs).toHaveLength(3);
      });
      expect(rowFor(container, "eu-central")).toBeTruthy();
    });

    it("drops the US map — a data center has no place on it, and a pin in the wrong "
       + "spot is worse than no map", async () => {
      const { container } = render(
        <RegionPicker groupName="x" current={["na-east"]} onSubmit={vi.fn()} onCancel={vi.fn()} />,
      );

      await waitFor(() => expect(container.querySelector(".regionChecklist")).toBeTruthy());
      expect(container.querySelector(".regionMap")).toBeNull();
      expect(container.querySelectorAll(".regionPin")).toHaveLength(0);
    });

    it("uses the data-center vocabulary in its copy", async () => {
      render(<RegionPicker groupName="x" current={["na-east"]} onSubmit={vi.fn()} onCancel={vi.fn()} />);
      expect(await screen.findByRole("button", { name: /Save data centers/ })).toBeInTheDocument();
    });

    it("surfaces a placement the deployment no longer offers so it can be removed", async () => {
      const { container } = render(
        <RegionPicker
          groupName="x"
          current={["na-east", "retired-dc"]}
          onSubmit={vi.fn()}
          onCancel={vi.fn()}
        />,
      );

      await waitFor(() =>
        expect(container.querySelector(".regionPicker__legacy")).toBeTruthy());
      expect(screen.getByText(/Not offered by this deployment/)).toBeInTheDocument();
    });
  });
});
