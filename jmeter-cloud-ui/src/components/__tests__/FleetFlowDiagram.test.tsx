import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";

import { FleetFlowDiagram, workerName } from "../FleetFlowDiagram";
import type { FleetAllocationEntry, WorkerStatus } from "../../api/runs";
import type { RegionCapacity } from "../../api/regions";

class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverStub }).ResizeObserver = ResizeObserverStub;

function setup(opts: {
    regions?: RegionCapacity[];
    applicationName?: string;
    value?: FleetAllocationEntry[];
    workerStatuses?: Record<string, WorkerStatus[]>;
    onWorkerClick?: (region: string, nodeIndex: number) => void;
    maxByRegion?: Record<string, number>;
} = {}) {
    const onWorkerClick = opts.onWorkerClick ?? vi.fn();
    const utils = render(
        <FleetFlowDiagram
            regions={opts.regions ?? defaultRegions()}
            applicationName={opts.applicationName ?? "checkout-svc"}
            value={opts.value ?? []}
            workerStatuses={opts.workerStatuses}
            onWorkerClick={onWorkerClick}
            maxByRegion={opts.maxByRegion}
        />,
    );
    return { ...utils, onWorkerClick };
}

function defaultRegions(): RegionCapacity[] {
    return [
        { region: "local-east-1", idlePods: 20, totalPods: 20, lostPods: 0 },
        { region: "local-west-2", idlePods: 20, totalPods: 20, lostPods: 0 },
    ];
}

describe("FleetFlowDiagram — empty / loading / error", () => {
    it("renders the no-regions hint when regions list is empty", () => {
        render(<FleetFlowDiagram regions={[]} value={[]} />);
        expect(screen.getByText(/No regions registered yet/i)).toBeInTheDocument();
    });

    it("renders the loading state", () => {
        render(<FleetFlowDiagram regions={[]} value={[]} loading />);
        expect(screen.getByText(/Loading regions…/i)).toBeInTheDocument();
    });

    it("renders the error state", () => {
        render(
            <FleetFlowDiagram
                regions={[]} value={[]}
                error="cannot reach /regions"
            />,
        );
        expect(screen.getByText(/cannot reach \/regions/)).toBeInTheDocument();
    });
});

describe("FleetFlowDiagram — topology", () => {
    it("does not render a global-orchestrator node", () => {
        setup();
        expect(screen.queryByText(/global-orchestrator/i)).toBeNull();
        expect(document.querySelector('.fleetFlow__global')).toBeNull();
    });

    // UX7 — lane dividers were removed in the flow refactor. Regions
    // now stand on their own and edges visually separate header from
    // pods. Two new assertions: each region renders ONE header, and
    // each allocated worker renders its own pod node.
    it("renders one header per region (no lane dividers)", () => {
        setup();
        expect(document.querySelectorAll('[data-testid="fleetFlow-lane-divider"]'))
            .toHaveLength(0);
        // Two regions → two header nodes (data-region attr on the
        // header card; the visually-hidden aria table also names the
        // regions but doesn't carry data-region, so the count is exact).
        expect(document.querySelectorAll('.fleetFlow__region[data-region]'))
            .toHaveLength(2);
    });

    it("renders one PodNode per allocated worker", () => {
        setup({
            value: [
                { region: "local-east-1", count: 3 },
                { region: "local-west-2", count: 2 },
            ],
        });
        // 3 + 2 = 5 pod-tile buttons total.
        expect(document.querySelectorAll('button[data-node-index]'))
            .toHaveLength(5);
    });
});

// UX3 hybrid view — in-node +/- DeltaControls were removed. The form
// sibling owns add/remove ergonomics. The diagram is purely read-only
// from this rework forward.
describe("FleetFlowDiagram — UX3 hybrid (no in-node controls)", () => {
    it("does NOT render in-node add/remove inputs (moved to FleetAllocationFormView)", () => {
        setup({ value: [{ region: "local-east-1", count: 2 }] });
        expect(screen.queryByLabelText(/add workers to local-east-1/i)).toBeNull();
        expect(screen.queryByLabelText(/remove workers from local-east-1/i)).toBeNull();
        expect(screen.queryByLabelText(/number of workers to add to local-east-1/i)).toBeNull();
    });

    it("shows 'max N' from maxByRegion when supplied (instead of idlePods)", () => {
        setup({
            regions: [{ region: "local-east-1", idlePods: 5, totalPods: 5, lostPods: 0 }],
            maxByRegion: { "local-east-1": 8 },
        });
        // Capacity hint reads the app ceiling (8), not the live IDLE count (5).
        expect(screen.getByText(/max 8/i)).toBeInTheDocument();
        // The IDLE-now count surfaces as a secondary hint so the operator
        // can see how many pods would need spinning.
        expect(screen.getByText(/5 ready now/i)).toBeInTheDocument();
    });

    it("shows the 'workers not ready' chip when allocation > idlePods", () => {
        setup({
            regions: [{ region: "local-east-1", idlePods: 1, totalPods: 1, lostPods: 0 }],
            value: [{ region: "local-east-1", count: 3 }],
            maxByRegion: { "local-east-1": 4 },
        });
        expect(screen.getByText(/2 workers not ready/i)).toBeInTheDocument();
    });
});

describe("FleetFlowDiagram — worker tiles + status icons", () => {
    it("renders one tile per claimed worker", () => {
        setup({
            value: [
                { region: "local-east-1", count: 2 },
                { region: "local-west-2", count: 1 },
            ],
        });
        expect(document.querySelectorAll('button[data-node-index]')).toHaveLength(3);
    });

    it("each tile shows {app}-{region}-worker-{N}", () => {
        setup({
            applicationName: "checkout-svc",
            value: [{ region: "local-east-1", count: 1 }],
        });
        const tile = document.querySelector('button[data-region="local-east-1"][data-node-index="0"]');
        expect(tile).toHaveAttribute("data-pod-name", "checkout-svc-local-east-1-worker-1");
        expect(tile).toHaveTextContent("checkout-svc");
        expect(tile).toHaveTextContent("worker-1");
    });

    it("tile carries the worker status as data-status (defaults to READY)", () => {
        setup({ value: [{ region: "local-east-1", count: 1 }] });
        const tile = document.querySelector('button[data-region="local-east-1"][data-node-index="0"]');
        expect(tile).toHaveAttribute("data-status", "READY");
    });

    it("explicit workerStatuses override the READY default", () => {
        setup({
            value: [{ region: "local-east-1", count: 2 }],
            workerStatuses: { "local-east-1": ["HEALTHY", "UNHEALTHY"] },
        });
        const t0 = document.querySelector('button[data-region="local-east-1"][data-node-index="0"]');
        const t1 = document.querySelector('button[data-region="local-east-1"][data-node-index="1"]');
        expect(t0).toHaveAttribute("data-status", "HEALTHY");
        expect(t1).toHaveAttribute("data-status", "UNHEALTHY");
    });

    it("clicking a tile invokes onWorkerClick(region, nodeIndex)", () => {
        const onWorkerClick = vi.fn();
        setup({
            value: [{ region: "local-east-1", count: 2 }],
            onWorkerClick,
        });
        const tile = document.querySelector('button[data-region="local-east-1"][data-node-index="1"]') as HTMLButtonElement;
        fireEvent.click(tile);
        expect(onWorkerClick).toHaveBeenCalledWith("local-east-1", 1);
    });

    it("renders all 20 tiles when allocation hits the cap", () => {
        setup({ value: [{ region: "local-east-1", count: 20 }] });
        expect(
            document.querySelectorAll('button[data-region="local-east-1"][data-node-index]'),
        ).toHaveLength(20);
    });
});

describe("FleetFlowDiagram — cap hint", () => {
    it("renders 'max N' inside the region card", () => {
        setup();
        const hints = document.querySelectorAll('.fleetFlow__capHint');
        expect(hints.length).toBeGreaterThan(0);
        expect(hints[0]).toHaveTextContent("max 20");
    });
});

describe("workerName helper", () => {
    it("composes {app}-{region}-worker-{N}", () => {
        expect(workerName("checkout-svc", "local-east-1", 1))
            .toBe("checkout-svc-local-east-1-worker-1");
    });

    it("falls back to 'app' on empty input", () => {
        expect(workerName("", "local-east-1", 1)).toBe("app-local-east-1-worker-1");
        expect(workerName("   ", "local-east-1", 1)).toBe("app-local-east-1-worker-1");
    });

    it("trims surrounding whitespace from app", () => {
        expect(workerName("  api  ", "us-west-2", 3)).toBe("api-us-west-2-worker-3");
    });
});

describe("FleetFlowDiagram — accessibility", () => {
    it("the figure carries an aria-label summarising the topology", () => {
        setup({ value: [{ region: "local-east-1", count: 2 }] });
        const fig = screen.getByRole("img");
        expect(fig).toHaveAttribute("aria-label", expect.stringContaining("2 region"));
        expect(fig).toHaveAttribute("aria-label", expect.stringContaining("2 pod"));
    });

    it("visually-hidden table mirrors region capacity for SR users", () => {
        setup({ value: [{ region: "local-east-1", count: 3 }] });
        const table = document.querySelector('table[aria-label="Fleet allocation by region"]');
        expect(table).not.toBeNull();
        const row = within(table as HTMLElement).getByRole("row", { name: /local-east-1/i });
        expect(row).toHaveTextContent("local-east-1");
        expect(row).toHaveTextContent("3");
    });
});
