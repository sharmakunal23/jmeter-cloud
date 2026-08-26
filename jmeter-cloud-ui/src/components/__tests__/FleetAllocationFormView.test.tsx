import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { FleetAllocationFormView } from "../FleetAllocationFormView";
import type { FleetAllocationEntry, WorkerStatus } from "../../api/runs";
import type { RegionCapacity } from "../../api/regions";

function setup(opts: {
    regions?: RegionCapacity[];
    value?: FleetAllocationEntry[];
    workerStatuses?: Record<string, WorkerStatus[]>;
    onAddWorkers?: (region: string, n: number) => void;
    onRemoveWorkers?: (region: string, n: number) => void;
} = {}) {
    const onAddWorkers = opts.onAddWorkers ?? vi.fn();
    const onRemoveWorkers = opts.onRemoveWorkers ?? vi.fn();
    const utils = render(
        <FleetAllocationFormView
            regions={opts.regions ?? defaultRegions()}
            value={opts.value ?? []}
            workerStatuses={opts.workerStatuses}
            onAddWorkers={onAddWorkers}
            onRemoveWorkers={onRemoveWorkers}
        />,
    );
    return { ...utils, onAddWorkers, onRemoveWorkers };
}

function defaultRegions(): RegionCapacity[] {
    return [
        { region: "local-east-1", idlePods: 20, totalPods: 20, lostPods: 0 },
        { region: "local-west-2", idlePods: 20, totalPods: 20, lostPods: 0 },
    ];
}

describe("FleetAllocationFormView — empty / loading / error", () => {
    it("renders the no-regions hint", () => {
        render(
            <FleetAllocationFormView
                regions={[]} value={[]}
                onAddWorkers={vi.fn()} onRemoveWorkers={vi.fn()}
            />,
        );
        expect(screen.getByText(/No regions registered yet/i)).toBeInTheDocument();
    });

    it("renders the loading state", () => {
        render(
            <FleetAllocationFormView
                regions={[]} value={[]}
                onAddWorkers={vi.fn()} onRemoveWorkers={vi.fn()}
                loading
            />,
        );
        expect(screen.getByText(/Loading regions…/i)).toBeInTheDocument();
    });
});

describe("FleetAllocationFormView — table + delta", () => {
    it("renders one row per region", () => {
        setup();
        expect(document.querySelectorAll('.fleetForm__table tbody tr')).toHaveLength(2);
    });

    it("typing 3 in the Add input and clicking + invokes onAddWorkers", () => {
        const onAddWorkers = vi.fn();
        setup({ onAddWorkers });
        const input = screen.getByLabelText(/number of workers to add to local-east-1/i);
        fireEvent.change(input, { target: { value: "3" } });
        fireEvent.click(screen.getByLabelText(/add workers to local-east-1/i));
        expect(onAddWorkers).toHaveBeenCalledWith("local-east-1", 3);
    });

    it("typing > remaining clamps the commit", () => {
        const onAddWorkers = vi.fn();
        setup({
            value: [{ region: "local-east-1", count: 5 }],
            onAddWorkers,
        });
        const input = screen.getByLabelText(/number of workers to add to local-east-1/i);
        fireEvent.change(input, { target: { value: "25" } });
        fireEvent.click(screen.getByLabelText(/add workers to local-east-1/i));
        expect(onAddWorkers).toHaveBeenCalledWith("local-east-1", 15);
    });

    it("Remove invokes onRemoveWorkers and is disabled when claimed=0", () => {
        const onRemoveWorkers = vi.fn();
        setup({
            value: [{ region: "local-east-1", count: 4 }],
            onRemoveWorkers,
        });
        const east = screen.getByLabelText(/remove workers from local-east-1/i);
        expect(east).not.toBeDisabled();
        const west = screen.getByLabelText(/remove workers from local-west-2/i);
        expect(west).toBeDisabled();

        const input = screen.getByLabelText(/number of workers to remove from local-east-1/i);
        fireEvent.change(input, { target: { value: "2" } });
        fireEvent.click(east);
        expect(onRemoveWorkers).toHaveBeenCalledWith("local-east-1", 2);
    });

    // Status column dropped (info lives in the flow view).
    // Capacity column dropped too (HeaderNode in the flow view
    // already shows X/max + "ready now" + the "+N to spin" chip).
    it("renders exactly 3 columns: Region · Add · Remove", () => {
        setup({
            value: [{ region: "local-east-1", count: 3 }],
            workerStatuses: { "local-east-1": ["HEALTHY", "HEALTHY", "UNHEALTHY"] },
        });
        expect(document.querySelectorAll('.fleetForm__statusChip')).toHaveLength(0);
        const headers = Array.from(
            document.querySelectorAll('.fleetForm__table thead th'),
        ).map((h) => h.textContent);
        expect(headers).toEqual(["Region", "Add", "Remove"]);
    });
});

describe("FleetAllocationFormView — accessibility", () => {
    it("is identified as a region with an aria-label", () => {
        setup();
        expect(
            screen.getByRole("region", { name: "Fleet allocation form view" }),
        ).toBeInTheDocument();
    });
});
