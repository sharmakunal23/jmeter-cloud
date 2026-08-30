import { useEffect, useMemo } from "react";
import {
    ReactFlow,
    ReactFlowProvider,
    Background,
    Controls,
    Handle,
    Position,
    useReactFlow,
    type Edge,
    type Node,
    type NodeProps,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import type { FleetAllocationEntry, RegionShortfall, WorkerStatus } from "../api/runs";
import type { RegionCapacity } from "../api/regions";
import { utilizationTier } from "../util/utilization";

/**
 * Fleet allocation and visualization, lane-style.
 *
 * <p>One column per region; each region card carries its name,
 * capacity, Add/Remove controls, and a grid of worker tiles below.
 * Vertical dotted dividers separate region columns. Pods live INSIDE
 * their parent region card so a 20-pod region wraps cleanly without
 * overlap.
 *
 * <h3>Add / Remove vs. set-count</h3>
 * The original stepper let the operator type an absolute count
 * ("set this region to 5 pods"). It was later split into two
 * delta controls — "Add N" / "Remove N" — to mirror the mid-test
 * scaling operations the backend will expose later
 * ({@code POST/DELETE /api/v1/runs/{id}/members}). Add snapshots the
 * current global properties into each new worker; Remove drains from
 * the end of the array. Removing during a live run is a backend
 * concern (DRAINING status); pre-launch removal is a state edit.
 *
 * <h3>Pod naming (preview vs. post-launch)</h3>
 * Pre-launch the diagram synthesizes worker labels of the form
 * <code>{app}-{region}-worker-{N}</code>. The post-launch canonical
 * format adds a 6-char run-id segment in the middle:
 * <code>{app}-{runId6}-{region}-worker-{N}</code>.
 */

// Flow layout constants. Each region is now SPLIT across two node
// types (HeaderNode + one PodNode per worker) wired by edges, so the
// diagram reads like a real tree instead of a card-with-grid.
const HEADER_NODE_WIDTH   = 280;
const HEADER_NODE_HEIGHT  = 110;
const POD_NODE_WIDTH      = 130;
const POD_NODE_HEIGHT     = 64;
const POD_H_GAP           = 16;
const POD_V_GAP           = 14;
const HEADER_TO_POD_GAP   = 60;
const PODS_PER_ROW        = 4;

// Multi-row region layout. 1-3 regions fit comfortably on a
// single row; 4+ wraps so individual headers stay readable instead of
// shrinking with fitView.
const REGIONS_PER_ROW     = 3;
const REGION_H_GAP        = 72;
const REGION_V_GAP        = 110; // headroom between two rows of regions, accounting for the tallest pod stack

export interface FleetFlowDiagramProps {
    regions: RegionCapacity[];
    /** Picked from the form's application dropdown. */
    applicationName?: string;
    value: FleetAllocationEntry[];
    /**
     * Per-region status arrays. Index `i` is the status of worker `i`
     * in that region. Missing → all READY. Pre-launch the parent always
     * passes READY for every worker; post-launch it'll mirror the
     * backend's heartbeat snapshots.
     */
    workerStatuses?: Record<string, WorkerStatus[]>;
    /**
     * UX-DYNAMICS T2 — per-region "snapshot ≠ current globals" flags;
     * index `i` marks worker `i`. A flagged tile renders a corner badge
     * so per-worker property overrides are visible without opening the
     * drawer. Missing → no badges.
     */
    overrideFlags?: Record<string, boolean[]>;
    /**
     * When set, the diagram
     * renders a per-worker click target but does NOT expose any inline
     * +/- controls — those moved to the sibling allocation form so
     * editing the count doesn't churn the React Flow viewport. Kept
     * optional so the Capacity tab (which still uses the in-node
     * controls in read-only mode) keeps working without churn.
     */
    onWorkerClick?: (region: string, nodeIndex: number) => void;
    /**
     * Per-region capacity ceiling from the application group's
     * `capacity[]` grid. Displayed as the denominator in "X / max"
     * instead of IDLE-pod count, so the operator sees the policy
     * ceiling, not the live registry view.
     */
    maxByRegion?: Record<string, number>;
    shortfall?: RegionShortfall[];
    loading?: boolean;
    error?: string | null;
}

export function FleetFlowDiagram(props: FleetFlowDiagramProps) {
    return (
        <ReactFlowProvider>
            <FleetFlowDiagramInner {...props} />
        </ReactFlowProvider>
    );
}

function FleetFlowDiagramInner({
    regions,
    applicationName,
    value,
    workerStatuses,
    overrideFlags,
    onWorkerClick,
    maxByRegion,
    shortfall,
    loading,
    error,
}: FleetFlowDiagramProps) {
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

    const appLabel = (applicationName ?? "").trim() || "app";

    const { nodes, edges } = useMemo(
        () => buildGraph({
            regions, countsByRegion, shortfallByRegion,
            workerStatuses: workerStatuses ?? {},
            overrideFlags: overrideFlags ?? {},
            appLabel, onWorkerClick, maxByRegion,
        }),
        [regions, countsByRegion, shortfallByRegion, workerStatuses,
            overrideFlags, appLabel, onWorkerClick, maxByRegion],
    );

    // Auto-refit whenever the node count or region count changes.
    // ReactFlow's `fitView` prop only fires on mount; without this, adding
    // a worker via the form panel grows the diagram off-viewport until
    // the operator manually hits the Fit View control.
    const refitKey = nodes.length + "|" + regions.length;

    const ariaSummary = useMemo(() => {
        const totalPods = value.reduce((acc, e) => acc + e.count, 0);
        return `Fleet diagram: ${regions.length} region${regions.length === 1 ? "" : "s"} known, ` +
               `${value.length} allocated, ${totalPods} pod${totalPods === 1 ? "" : "s"} total.`;
    }, [regions.length, value]);

    if (loading && regions.length === 0) {
        return (
            <div className="fleetFlow fleetFlow--loading">
                <p className="ink-soft">Loading regions…</p>
            </div>
        );
    }

    if (error && regions.length === 0) {
        return (
            <div className="fleetFlow fleetFlow--error">
                <p className="text--error">{error}</p>
            </div>
        );
    }

    if (regions.length === 0) {
        return (
            <div className="fleetFlow fleetFlow--empty">
                <p className="ink-soft">
                    No regions registered yet — start at least one local-orchestrator
                    and wait for it to self-register.
                </p>
            </div>
        );
    }

    return (
        <figure
            className="fleetFlow"
            role="img"
            aria-label={ariaSummary}
        >
            <ReactFlow
                nodes={nodes}
                edges={edges}
                nodeTypes={nodeTypes}
                fitView
                fitViewOptions={{ padding: 0.2 }}
                nodesDraggable={false}
                nodesConnectable={false}
                elementsSelectable
                proOptions={{ hideAttribution: true }}
                aria-hidden="true"
            >
                <Background gap={24} size={1} />
                <Controls showInteractive={false} />
                <AutoFitOnResize refitKey={refitKey} />
            </ReactFlow>

            <table className="visuallyHidden" aria-label="Fleet allocation by region">
                <thead>
                    <tr>
                        <th>Region</th>
                        <th>Claimed</th>
                        <th>Idle</th>
                        <th>Total</th>
                    </tr>
                </thead>
                <tbody>
                    {regions.map((r) => (
                        <tr key={r.region}>
                            <td>{r.region}</td>
                            <td>{countsByRegion.get(r.region) ?? 0}</td>
                            <td>{r.idlePods}</td>
                            <td>{r.totalPods}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </figure>
    );
}

// ── Graph construction ─────────────────────────────────────────────

interface BuildGraphArgs {
    regions: RegionCapacity[];
    countsByRegion: Map<string, number>;
    shortfallByRegion: Map<string, RegionShortfall>;
    workerStatuses: Record<string, WorkerStatus[]>;
    overrideFlags: Record<string, boolean[]>;
    appLabel: string;
    onWorkerClick?: (region: string, nodeIndex: number) => void;
    maxByRegion?: Record<string, number>;
}

/**
 * Emits a HEADER node per region + one POD node per worker, plus
 * edges connecting each header to its pods. When there are more
 * than {@link REGIONS_PER_ROW} regions, wraps to additional rows.
 */
function buildGraph({
    regions, countsByRegion, shortfallByRegion, workerStatuses, overrideFlags,
    appLabel, onWorkerClick, maxByRegion,
}: BuildGraphArgs): { nodes: Node[]; edges: Edge[] } {
    const sortedRegions = [...regions].sort((a, b) => a.region.localeCompare(b.region));
    const totalRegions = sortedRegions.length;

    const nodes: Node[] = [];
    const edges: Edge[] = [];
    if (totalRegions === 0) return { nodes, edges };

    // Compute per-region row width (number of pods × pod-cell-width)
    // so each region row centres on its header.
    function podGridDimensions(claimed: number): { width: number; height: number; rows: number } {
        const podsInRow = Math.min(Math.max(1, claimed), PODS_PER_ROW);
        const rows = Math.max(1, Math.ceil(claimed / PODS_PER_ROW));
        const width = podsInRow * POD_NODE_WIDTH + (podsInRow - 1) * POD_H_GAP;
        const height = rows * POD_NODE_HEIGHT + (rows - 1) * POD_V_GAP;
        return { width, height, rows };
    }

    // Walk regions row-by-row so a wide allocation in row 1 doesn't
    // collide with row 2.
    const rowsOfRegions: typeof sortedRegions[] = [];
    for (let i = 0; i < totalRegions; i += REGIONS_PER_ROW) {
        rowsOfRegions.push(sortedRegions.slice(i, i + REGIONS_PER_ROW));
    }

    let cursorY = 0;
    rowsOfRegions.forEach((row) => {
        // Each region's effective width = max(HEADER_NODE_WIDTH, pod grid width).
        const widths = row.map((r) => {
            const claimed = countsByRegion.get(r.region) ?? 0;
            const dims = podGridDimensions(claimed);
            return Math.max(HEADER_NODE_WIDTH, dims.width);
        });
        const rowWidth = widths.reduce((acc, w) => acc + w, 0)
            + Math.max(0, row.length - 1) * REGION_H_GAP;
        const rowStartX = -rowWidth / 2;
        let cursorX = rowStartX;
        let tallestPodStack = 0;

        row.forEach((r, regionIdxInRow) => {
            const claimed = countsByRegion.get(r.region) ?? 0;
            const sf = shortfallByRegion.get(r.region) ?? null;
            const statuses = workerStatuses[r.region] ?? [];
            const dims = podGridDimensions(claimed);
            const regionWidth = widths[regionIdxInRow];
            // Centre the header above the pod grid (or itself when no pods).
            const headerX = cursorX + (regionWidth - HEADER_NODE_WIDTH) / 2;

            const headerId = `header:${r.region}`;
            nodes.push({
                id: headerId,
                type: "headerNode",
                position: { x: headerX, y: cursorY },
                data: {
                    region: r.region,
                    idlePods: r.idlePods,
                    totalPods: r.totalPods,
                    lostPods: r.lostPods,
                    claimed,
                    shortfall: sf,
                    maxAvailable: maxByRegion?.[r.region] ?? null,
                },
                draggable: false,
            });

            // Pod row(s): centred under the header, stacked rows of PODS_PER_ROW.
            // Per-pod edges scaled badly: with 10+ pods the
            // smoothstep curves routed through the gaps between tiles,
            // making the diagram noisy. Instead we draw ONE trunk edge
            // per region (header → invisible anchor at the centre-top of
            // the pod block) and let spatial proximity carry "these pods
            // belong to this region". No per-pod edges any more.
            if (claimed > 0) {
                const anchorId = `anchor:${r.region}`;
                const anchorX = cursorX + regionWidth / 2 - 1; // 2 px wide centred
                const anchorY = cursorY + HEADER_NODE_HEIGHT + HEADER_TO_POD_GAP - 2;
                nodes.push({
                    id: anchorId,
                    type: "trunkAnchor",
                    position: { x: anchorX, y: anchorY },
                    data: {},
                    draggable: false,
                    selectable: false,
                });
                edges.push({
                    id: `e:${headerId}->${anchorId}`,
                    source: headerId,
                    target: anchorId,
                    type: "smoothstep",
                    animated: false,
                    focusable: false,
                    selectable: false,
                    style: {
                        stroke: "var(--fleetFlow-edge, #6b7280)",
                        strokeWidth: 1.4,
                        opacity: 0.7,
                    },
                });
                for (let i = 0; i < claimed; i++) {
                    const rowIdx = Math.floor(i / PODS_PER_ROW);
                    const colIdx = i % PODS_PER_ROW;
                    const podsInThisRow = Math.min(claimed - rowIdx * PODS_PER_ROW, PODS_PER_ROW);
                    const podRowWidth = podsInThisRow * POD_NODE_WIDTH
                        + (podsInThisRow - 1) * POD_H_GAP;
                    const podRowStartX = cursorX + (regionWidth - podRowWidth) / 2;
                    const podX = podRowStartX + colIdx * (POD_NODE_WIDTH + POD_H_GAP);
                    const podY = cursorY + HEADER_NODE_HEIGHT + HEADER_TO_POD_GAP
                        + rowIdx * (POD_NODE_HEIGHT + POD_V_GAP);
                    const fullName = workerName(appLabel, r.region, i + 1);
                    const podId = `pod:${r.region}:${i}`;
                    nodes.push({
                        id: podId,
                        type: "podNode",
                        position: { x: podX, y: podY },
                        data: {
                            region: r.region,
                            nodeIndex: i,
                            fullName,
                            appLabel,
                            workerLabel: `worker-${i + 1}`,
                            status: statuses[i] ?? "READY",
                            override: (overrideFlags[r.region] ?? [])[i] === true,
                            onWorkerClick,
                        },
                        draggable: false,
                    });
                }
            }
            tallestPodStack = Math.max(tallestPodStack, dims.height);
            cursorX += regionWidth + REGION_H_GAP;
        });

        cursorY += HEADER_NODE_HEIGHT + HEADER_TO_POD_GAP + tallestPodStack + REGION_V_GAP;
    });

    return { nodes, edges };
}

// ── Custom node components ─────────────────────────────────────────

const nodeTypes = {
    headerNode: HeaderNode,
    podNode: PodNode,
    trunkAnchor: TrunkAnchorNode,
};

// Invisible target node for the per-region trunk edge. The edge
// runs header → this anchor (positioned just above the pod block's top
// centre) so the visual is "one connector dropping into the pod
// cluster" instead of N criss-crossing per-pod connectors.
function TrunkAnchorNode() {
    return (
        <div
            style={{ width: 2, height: 2, pointerEvents: "none" }}
            aria-hidden="true"
        >
            <Handle
                type="target"
                position={Position.Top}
                id="top"
                style={{ opacity: 0, pointerEvents: "none" }}
                isConnectable={false}
            />
        </div>
    );
}

// HeaderNode keeps the visual identity of the old region card
// (the user explicitly asked to preserve it). It's now a true node,
// disconnected from the pod grid; pods live in their own nodes wired
// by edges, which gives the diagram its tree-style "flow" feel.
interface HeaderNodeData {
    region: string;
    idlePods: number;
    totalPods: number;
    lostPods: number;
    claimed: number;
    shortfall: RegionShortfall | null;
    maxAvailable: number | null;
}

function HeaderNode({ data }: NodeProps) {
    const d = data as unknown as HeaderNodeData;
    const ceiling = d.maxAvailable ?? d.idlePods;
    const tier = utilizationTier(d.claimed, ceiling);
    const hasShortfall = d.shortfall != null;
    const needsSpin = d.claimed > d.idlePods;

    return (
        <div
            className={`fleetFlow__region fleetFlow__region--util-${tier} ${hasShortfall ? "fleetFlow__region--shortfall" : ""}`}
            data-region={d.region}
            data-util-tier={tier}
            style={{ width: HEADER_NODE_WIDTH }}
        >
            <div className="fleetFlow__regionHead">
                <span className="fleetFlow__regionName">{d.region}</span>
                <span
                    className={`fleetFlow__regionCapacity ${ceiling === 0 ? "fleetFlow__regionCapacity--zero" : ""}`}
                >
                    {d.claimed} / {ceiling}
                    {d.lostPods > 0 ? <span className="fleetFlow__regionLost"> · {d.lostPods} LOST</span> : null}
                </span>
            </div>
            <div className="fleetFlow__capHint ink-soft">
                max {ceiling}
                {d.maxAvailable != null && (
                    <span> · {d.idlePods} ready now</span>
                )}
            </div>
            {needsSpin && (
                <div className="fleetFlow__needsSpin" role="note">
                    +{d.claimed - d.idlePods} worker{d.claimed - d.idlePods === 1 ? "" : "s"} not ready — spin on launch
                </div>
            )}
            {hasShortfall && (
                <div className="fleetFlow__shortfall" role="alert">
                    Requested {d.shortfall!.requested}, only {d.shortfall!.claimed} claimable
                </div>
            )}
            {/* Bottom-edge source handle so the edges land cleanly on the
                header's bottom centre. Hidden visually (React Flow renders
                handles as small dots otherwise). */}
            <Handle
                type="source"
                position={Position.Bottom}
                id="bottom"
                style={{ opacity: 0, pointerEvents: "none" }}
                isConnectable={false}
            />
        </div>
    );
}

interface PodNodeData {
    region: string;
    nodeIndex: number;
    fullName: string;
    appLabel: string;
    workerLabel: string;
    status: WorkerStatus;
    override: boolean;
    onWorkerClick?: (region: string, nodeIndex: number) => void;
}

function PodNode({ data }: NodeProps) {
    const d = data as unknown as PodNodeData;
    return (
        <button
            type="button"
            className="fleetFlow__podTile fleetFlow__podTile--node"
            data-region={d.region}
            data-node-index={d.nodeIndex}
            data-pod-name={d.fullName}
            data-status={d.status}
            aria-label={`${d.fullName}, status ${d.status}${d.override ? ", has per-worker properties" : ""} — click to set per-worker properties`}
            title={`${d.fullName} · ${d.status}`}
            onClick={() => d.onWorkerClick?.(d.region, d.nodeIndex)}
            style={{ width: POD_NODE_WIDTH, height: POD_NODE_HEIGHT }}
        >
            <Handle
                type="target"
                position={Position.Top}
                id="top"
                style={{ opacity: 0, pointerEvents: "none" }}
                isConnectable={false}
            />
            {d.override && (
                <span className="fleetFlow__podTileOverride" title="has per-worker properties" aria-hidden="true">≡</span>
            )}
            <WorkerStatusIcon status={d.status} />
            <span className="fleetFlow__podTileApp">{d.appLabel}</span>
            <span className="fleetFlow__podTileWorker">{d.workerLabel}</span>
        </button>
    );
}

/**
 * Re-runs ReactFlow's `fitView` on container resize:
 *   1. On any change to {@code refitKey} (passed in from the parent;
 *      includes the node count + region count) — so adding a worker
 *      from the form panel keeps the diagram centred.
 *   2. When the wrapping element's width changes (Hide Controls toggle
 *      expands the viz column).
 */
function AutoFitOnResize({ refitKey }: { refitKey: string }) {
    const { fitView } = useReactFlow();
    // Refit whenever the node/region count shifts. A short delay
    // gives React Flow a tick to render the new node positions before
    // we measure them.
    useEffect(() => {
        const t = setTimeout(() => {
            void fitView({ padding: 0.2, duration: 200 });
        }, 30);
        return () => clearTimeout(t);
    }, [fitView, refitKey]);

    // Container-resize refit (Hide Controls toggle, window resize).
    useEffect(() => {
        const target = document.querySelector<HTMLElement>(".fleetFlow");
        if (!target || typeof ResizeObserver !== "function") return;
        let raf = 0;
        const ro = new ResizeObserver(() => {
            cancelAnimationFrame(raf);
            raf = requestAnimationFrame(() => {
                void fitView({ padding: 0.2, duration: 200 });
            });
        });
        ro.observe(target);
        return () => {
            ro.disconnect();
            cancelAnimationFrame(raf);
        };
    }, [fitView]);
    return null;
}

// ── Add / Remove delta controls ────────────────────────────────────

// DeltaControls + DeltaButton (the in-node +/-) removed.
// Edit ergonomics live in the sibling allocation form so adjusting the
// count doesn't churn the React Flow viewport.

// ── Worker status icon ─────────────────────────────────────────────

function WorkerStatusIcon({ status }: { status: WorkerStatus }) {
    const cls = `fleetFlow__statusIcon fleetFlow__statusIcon--${status.toLowerCase()}`;
    // Glyph chosen to read at small size: ● filled circle for terminal/healthy
    // states, ◐ half-circle for in-flight, ○ open circle for pre-launch ready.
    const glyph =
        status === "READY"      ? "○"
      : status === "INITIATING" ? "◐"
      : status === "HEALTHY"    ? "●"
      : status === "UNHEALTHY"  ? "✗"
      : status === "DRAINING"   ? "◇"
      : /* STOPPED */            "■";
    return (
        <span className={cls} aria-hidden="true" data-status={status}>
            {glyph}
        </span>
    );
}

// ── Helpers ────────────────────────────────────────────────────────

/**
 * Pre-launch worker label. Mirrors the canonical post-launch format
 * {@code {app}-{runId6}-{region}-worker-{N}} but omits the runId6
 * segment (the operator hasn't launched yet, so no runId exists).
 */
export function workerName(app: string, region: string, oneBasedIndex: number): string {
    const safeApp = app && app.trim() ? app.trim() : "app";
    return `${safeApp}-${region}-worker-${oneBasedIndex}`;
}
