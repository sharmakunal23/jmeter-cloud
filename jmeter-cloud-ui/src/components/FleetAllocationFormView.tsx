import { useCallback, useMemo, useState } from "react";

import type { FleetAllocationEntry, RegionShortfall, WorkerStatus } from "../api/runs";
import type { RegionCapacity } from "../api/regions";

/**
 * Table-style allocator. Operator toggles between
 * the data-flow diagram and this compact form via the toolbar's
 * view-mode switcher. Same Add/Remove semantics as the diagram —
 * delegates to the parent's {@code onAddWorkers} / {@code onRemoveWorkers}
 * callbacks, which own the property snapshot + status tracking.
 */

export interface FleetAllocationFormViewProps {
    regions: RegionCapacity[];
    value: FleetAllocationEntry[];
    workerStatuses?: Record<string, WorkerStatus[]>;
    onAddWorkers: (region: string, n: number) => void;
    onRemoveWorkers: (region: string, n: number) => void;
    shortfall?: RegionShortfall[];
    loading?: boolean;
    error?: string | null;
    /**
     * Per-region capacity ceiling from the application group's
     * `capacity[]` grid (the pool the app runs on). When provided, the +/- buttons clamp at
     * `maxAvailable - claimed` (the ceiling) rather than the live
     * IDLE-pod count. Selecting more than IDLE-now is fine — the
     * shortfall flow (spinShortfall) handles the gap. Selecting
     * more than `maxAvailable` is forbidden.
     */
    maxByRegion?: Record<string, number>;
    /**
     * Legacy back-compat only — the Hide / Show Controls toggle lives in the
     * page header now, because on the form's own header it hid along with the
     * column it controlled. The form-header chrome no longer renders when only
     * one of these is passed.
     */
    controlsHidden?: boolean;
    onToggleControls?: () => void;
}

export function FleetAllocationFormView({
    regions, value, onAddWorkers, onRemoveWorkers,
    shortfall, loading, error, maxByRegion,
    controlsHidden, onToggleControls,
}: FleetAllocationFormViewProps) {
    // WorkerStatuses is still accepted on the props interface
    // (callers pass it from the launcher) but the form no longer renders
    // statuses, so we don't destructure it here.
    const countsByRegion = useMemo(() => {
        const m = new Map<string, number>();
        for (const e of value) m.set(e.region, e.count);
        return m;
    }, [value]);

    const shortfallByRegion = useMemo(() => {
        const m = new Map<string, RegionShortfall>();
        for (const s of shortfall ?? []) m.set(s.region, s);
        return m;
    }, [shortfall]);

    if (loading && regions.length === 0) {
        return (
            <div className="fleetForm fleetForm--loading">
                <p className="ink-soft">Loading regions…</p>
            </div>
        );
    }

    if (error && regions.length === 0) {
        return (
            <div className="fleetForm fleetForm--error">
                <p className="text--error">{error}</p>
            </div>
        );
    }

    if (regions.length === 0) {
        return (
            <div className="fleetForm fleetForm--empty">
                <p className="ink-soft">
                    No regions registered yet — start at least one local-orchestrator
                    and wait for it to self-register.
                </p>
            </div>
        );
    }

    const sorted = [...regions].sort((a, b) => a.region.localeCompare(b.region));

    return (
        <div className="fleetForm" role="region" aria-label="Fleet allocation form view">
            {onToggleControls && (
                <div className="fleetForm__header">
                    <button
                        type="button"
                        className="btn btn--ghost fleetForm__hideControls"
                        onClick={onToggleControls}
                        title={controlsHidden ? "Show form pane (keyboard: [)" : "Hide form pane (keyboard: [)"}
                        aria-pressed={controlsHidden ?? false}
                    >
                        {controlsHidden ? "▸ Show Controls" : "◂ Hide Controls"}
                    </button>
                </div>
            )}
            <table className="fleetForm__table">
                <thead>
                    <tr>
                        <th>Region</th>
                        <th>Add</th>
                        <th>Remove</th>
                        {/* UX16 — Status column dropped (info lives in the
                            flow view's pod-tile borders).
                            UX20 — Capacity column dropped too. The flow
                            view's HeaderNode already shows
                            "X / max · Y ready now" + the "+N to spin"
                            chip; restating it here doubled the form
                            width with no new information. */}
                    </tr>
                </thead>
                <tbody>
                    {sorted.map((r) => {
                        const claimed = countsByRegion.get(r.region) ?? 0;
                        // When maxByRegion is supplied (app launcher),
                        // the ceiling is the group's per-region maxAvailable.
                        // Otherwise fall back to the IDLE-pod count (legacy
                        // capacity-tab view).
                        const ceiling = maxByRegion?.[r.region] ?? r.idlePods;
                        const sf = shortfallByRegion.get(r.region) ?? null;
                        const remainingCap = Math.max(0, ceiling - claimed);
                        // "workers not ready" indicator: when the
                        // operator has selected more pods than are currently
                        // IDLE, the gap needs a spin.
                        const needsSpin = claimed > r.idlePods;
                        return (
                            <tr key={r.region} className={sf ? "fleetForm__row--shortfall" : ""}>
                                <td className="fleetForm__regionCell">
                                    <span className="mono">{r.region}</span>
                                    {r.lostPods > 0 && (
                                        <small className="fleetForm__lost"> · {r.lostPods} LOST</small>
                                    )}
                                    {needsSpin && (
                                        <>
                                            {" "}
                                            <span
                                                className="fleetForm__needsSpin"
                                                title={`Need ${claimed - r.idlePods} more worker(s); will be spun on launch.`}
                                            >
                                                +{claimed - r.idlePods} to spin
                                            </span>
                                        </>
                                    )}
                                </td>
                                <td>
                                    <FormDeltaButton
                                        kind="add"
                                        symbol="+"
                                        max={remainingCap}
                                        ariaCount={`number of workers to add to ${r.region}`}
                                        ariaCommit={`add workers to ${r.region}`}
                                        onCommit={(n) => onAddWorkers(r.region, n)}
                                    />
                                </td>
                                <td>
                                    <FormDeltaButton
                                        kind="remove"
                                        symbol="−"
                                        max={claimed}
                                        ariaCount={`number of workers to remove from ${r.region}`}
                                        ariaCommit={`remove workers from ${r.region}`}
                                        onCommit={(n) => onRemoveWorkers(r.region, n)}
                                    />
                                </td>
                                {/* UX28 — shortfall "Requested N, only M
                                    claimable" cell removed; the flow view's
                                    HeaderNode already surfaces this. The
                                    row's red-tint (fleetForm__row--shortfall)
                                    class is still applied so the form row
                                    visually highlights the affected region. */}
                            </tr>
                        );
                    })}
                </tbody>
            </table>
        </div>
    );
}

interface FormDeltaButtonProps {
    kind: "add" | "remove";
    symbol: string;
    max: number;
    ariaCount: string;
    ariaCommit: string;
    onCommit: (n: number) => void;
}

function FormDeltaButton({
    kind, symbol, max, ariaCount, ariaCommit, onCommit,
}: FormDeltaButtonProps) {
    const [n, setN] = useState(1);
    const disabled = max < 1 || n < 1;
    const commit = useCallback(() => {
        if (disabled) return;
        onCommit(Math.min(n, max));
    }, [disabled, n, max, onCommit]);
    return (
        <span className={`fleetForm__delta fleetForm__delta--${kind}`}>
            <input
                type="number"
                className="fleetForm__deltaInput"
                min={1}
                value={n}
                onChange={(e) => {
                    const v = Number.parseInt(e.target.value, 10);
                    setN(Number.isNaN(v) ? 0 : Math.max(0, v));
                }}
                onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); commit(); } }}
                aria-label={ariaCount}
            />
            <button
                type="button"
                className={`fleetForm__deltaCommit fleetForm__deltaCommit--${kind}`}
                disabled={disabled}
                onClick={commit}
                aria-label={ariaCommit}
                title={`${symbol === "+" ? "Add" : "Remove"} ${n} (Enter)`}
            >{symbol}</button>
        </span>
    );
}

// RegionStatusSummary removed along with the Status column. The
// flow diagram already shows per-worker status via colour-coded pod-tile
// borders; restating it in the form was duplicative.
