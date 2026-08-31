import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { RegionPicker } from "../RegionPicker";
import { __resetPlatformCapabilitiesCache } from "../../hooks/usePlatformCapabilities";
import { clustersApi, type ClusterStatus } from "../../api/clusters";

vi.mock("../../api/clusters", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/clusters")>();
  return { ...actual, clustersApi: { ...actual.clustersApi, status: vi.fn() } };
});

const statusMock = vi.mocked(clustersApi.status);

function cluster(region: string, over: Partial<ClusterStatus> = {}): ClusterStatus {
  return {
    region,
    label: region.toUpperCase(),
    regionalUrl: `http://${region}:30088`,
    maxWorkers: 20,
    reservedWorkers: 5,
    provisionedWorkers: 2,
    probing: false,
    ...over,
  };
}

function rowFor(container: HTMLElement, regionId: string): HTMLElement {
  const row = [...container.querySelectorAll<HTMLElement>(".regionChecklist__row")]
    .find((el) => el.textContent?.includes(regionId));
  if (!row) throw new Error(`no checklist row for ${regionId}`);
  return row;
}

describe("RegionPicker (cluster picker, CLUSTER-CAPACITY)", () => {
  beforeEach(() => {
    __resetPlatformCapabilitiesCache();
    statusMock.mockResolvedValue([cluster("na-east"), cluster("na-west"), cluster("na-south")]);
  });
  afterEach(() => {
    vi.clearAllMocks();
    vi.unstubAllGlobals();
  });

  it("lists the registered clusters with their reservable headroom and pre-selects current", async () => {
    const { container } = render(
      <RegionPicker groupName="cps" current={["na-east"]} onSubmit={vi.fn()} onCancel={vi.fn()} />,
    );
    await waitFor(() =>
      expect(container.querySelectorAll('.regionChecklist input[type="checkbox"]')).toHaveLength(3));
    const inputs = container.querySelectorAll<HTMLInputElement>('.regionChecklist input[type="checkbox"]');
    expect([...inputs].filter((i) => i.checked)).toHaveLength(1);
    expect(rowFor(container, "na-east").textContent).toContain("15 of 20 workers reservable");
  });

  it("toggling a cluster on and saving submits the new selection", async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RegionPicker groupName="cps" current={["na-east"]} onSubmit={onSubmit} onCancel={vi.fn()} />,
    );
    await waitFor(() => rowFor(container, "na-west"));
    fireEvent.click(rowFor(container, "na-west").querySelector('input[type="checkbox"]')!);
    fireEvent.click(screen.getByRole("button", { name: /Save clusters/ }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
    const arg = onSubmit.mock.calls[0][0] as string[];
    expect(arg).toEqual(expect.arrayContaining(["na-east", "na-west"]));
  });

  it("caps the selection at maxClustersPerGroup — the third checkbox disables and the summary explains", async () => {
    const { container } = render(
      <RegionPicker groupName="cps" current={["na-east", "na-west"]} onSubmit={vi.fn()} onCancel={vi.fn()} />,
    );
    await waitFor(() => rowFor(container, "na-south"));
    const third = rowFor(container, "na-south").querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    expect(third.disabled).toBe(true);
    expect(screen.getByText(/detach one to pick another/)).toBeInTheDocument();
  });

  it("a locked cluster (has workers) cannot be deselected", async () => {
    const { container } = render(
      <RegionPicker
        groupName="cps"
        current={["na-east"]}
        lockedRegions={new Set(["na-east"])}
        onSubmit={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    await waitFor(() => rowFor(container, "na-east"));
    const input = rowFor(container, "na-east").querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    expect(input.disabled).toBe(true);
  });

  it("Save is disabled with no changes", async () => {
    const { container } = render(
      <RegionPicker groupName="cps" current={["na-east"]} onSubmit={vi.fn()} onCancel={vi.fn()} />,
    );
    await waitFor(() => rowFor(container, "na-east"));
    expect(screen.getByRole("button", { name: /Save clusters/ })).toBeDisabled();
  });

  it("detaching the last cluster is allowed — a group may hold none and re-attach later", async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RegionPicker groupName="cps" current={["na-east"]} onSubmit={onSubmit} onCancel={vi.fn()} />,
    );
    await waitFor(() => rowFor(container, "na-east"));
    fireEvent.click(rowFor(container, "na-east").querySelector('input[type="checkbox"]')!);
    expect(screen.getByText(/cannot launch runs until one is/)).toBeInTheDocument();

    const save = screen.getByRole("button", { name: /Save clusters/ });
    expect(save).toBeEnabled();
    fireEvent.click(save);
    expect(onSubmit).toHaveBeenCalledWith([]);
  });

  it("with no clusters registered it says so and points at the Clusters page", async () => {
    statusMock.mockResolvedValue([]);
    render(<RegionPicker groupName="cps" current={[]} onSubmit={vi.fn()} onCancel={vi.fn()} />);
    expect(await screen.findByText(/No clusters registered yet/)).toBeInTheDocument();
    expect(screen.getByText(/Register one on the Clusters page first/)).toBeInTheDocument();
  });
});
