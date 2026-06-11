import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import {
    RegionBadge,
    RegionBadgeList,
    PALETTE_SIZE,
    MAX_VISIBLE,
    deriveRegions,
    hashRegionToColor,
} from "../RegionBadge";
import type { Run } from "../../api/runs";

// ── helpers ─────────────────────────────────────────────────────────

function makeRun(overrides: Partial<Run> = {}): Run {
    return {
        runId: "01J000000000000000000000",
        originRegion: "us-east-1",
        testPlanBlobId: "blob-test-plan",
        dataFilesBlobId: null,
        initiatedBy: "test",
        labelFilter: [],
        state: "RUNNING",
        stateReason: null,
        createdAt: "2026-05-11T12:00:00Z",
        startedAt: "2026-05-11T12:00:01Z",
        completedAt: null,
        fleetMembers: [],
        ...overrides,
    } as Run;
}

function fleetMember(region: string, workerId = `worker-${region}`) {
    return {
        runId: "irrelevant",
        workerId,
        region,
        state: "RUNNING" as const,
        stateReason: null,
        createdAt: "2026-05-11T12:00:00Z",
    };
}

// ── RegionBadge ─────────────────────────────────────────────────────

describe("RegionBadge", () => {
    it("renders the region name as visible text", () => {
        render(<RegionBadge name="us-east-1" />);
        expect(screen.getByText("us-east-1")).toBeInTheDocument();
    });

    it("carries a region-prefixed aria-label so SR users hear what it represents", () => {
        render(<RegionBadge name="us-east-1" />);
        expect(screen.getByRole("status")).toHaveAttribute(
            "aria-label",
            "region: us-east-1",
        );
    });

    it("carries a data-region attribute for tests + click handlers", () => {
        render(<RegionBadge name="us-east-1" />);
        expect(screen.getByRole("status")).toHaveAttribute("data-region", "us-east-1");
    });

    it("the colored dot is aria-hidden so SR doesn't double-announce the badge", () => {
        const { container } = render(<RegionBadge name="us-east-1" />);
        const dot = container.querySelector(".regionBadge__dot");
        expect(dot).toHaveAttribute("aria-hidden", "true");
    });
});

describe("hashRegionToColor", () => {
    it("returns a stable index for the same name (deterministic)", () => {
        const a = hashRegionToColor("us-east-1");
        const b = hashRegionToColor("us-east-1");
        expect(a).toEqual(b);
    });

    it("returns a value in [0, PALETTE_SIZE)", () => {
        const samples = [
            "us-east-1",
            "us-west-2",
            "eu-west-1",
            "ap-south-1",
            "local-east-1",
            "local-west-2",
            "sa-east-1",
            "ca-central-1",
        ];
        for (const name of samples) {
            const idx = hashRegionToColor(name);
            expect(idx).toBeGreaterThanOrEqual(0);
            expect(idx).toBeLessThan(PALETTE_SIZE);
        }
    });

    it("handles empty + single-char + Unicode without throwing", () => {
        expect(hashRegionToColor("")).toBeGreaterThanOrEqual(0);
        expect(hashRegionToColor("x")).toBeGreaterThanOrEqual(0);
        expect(hashRegionToColor("eu-west-🌍")).toBeGreaterThanOrEqual(0);
    });

    it("local-east-1 lands on c5 (sky-blue) — regression guard for the green-vs-COMPLETED clash", () => {
        // c5 was emerald (#047857) which was identical to the .badge--ok green
        // used by the COMPLETED state badge. Swapped to sky-blue (#0369a1) so
        // the region pill and state pill never look alike at a glance. This
        // test pins the deterministic-hash → palette-index relationship so
        // a future palette reorder can't silently re-introduce the clash.
        expect(hashRegionToColor("local-east-1")).toEqual(5);
    });
});

describe("deriveRegions", () => {
    it("returns originRegion when no fleet members exist (skeleton state)", () => {
        const run = makeRun({ fleetMembers: [], originRegion: "us-east-1" });
        expect(deriveRegions(run)).toEqual(["us-east-1"]);
    });

    it("returns the single region when all members are colocated", () => {
        const run = makeRun({
            fleetMembers: [fleetMember("us-east-1"), fleetMember("us-east-1", "worker-2")],
        });
        expect(deriveRegions(run)).toEqual(["us-east-1"]);
    });

    it("returns distinct regions sorted alphabetically", () => {
        const run = makeRun({
            fleetMembers: [
                fleetMember("us-west-2"),
                fleetMember("us-east-1"),
                fleetMember("us-west-2", "worker-2"),
                fleetMember("eu-west-1"),
            ],
        });
        // Sort is alphabetical, not insertion-order
        expect(deriveRegions(run)).toEqual(["eu-west-1", "us-east-1", "us-west-2"]);
    });

    it("returns empty list when no members AND no originRegion", () => {
        const run = makeRun({ fleetMembers: [], originRegion: "" });
        expect(deriveRegions(run)).toEqual([]);
    });
});

// ── RegionBadgeList ─────────────────────────────────────────────────

describe("RegionBadgeList", () => {
    it("renders one pill per distinct region", () => {
        const run = makeRun({
            fleetMembers: [
                fleetMember("us-east-1"),
                fleetMember("us-west-2"),
                fleetMember("us-east-1", "worker-2"), // dupe
            ],
        });
        render(<RegionBadgeList run={run} />);

        const list = screen.getByRole("list");
        const items = within(list).getAllByRole("listitem");
        expect(items).toHaveLength(2);
        expect(within(list).getByText("us-east-1")).toBeInTheDocument();
        expect(within(list).getByText("us-west-2")).toBeInTheDocument();
    });

    it("has NO (N) count text anywhere — drops the legacy `region (count)` rendering", () => {
        const run = makeRun({
            fleetMembers: [
                fleetMember("us-east-1"),
                fleetMember("us-east-1", "worker-2"),
                fleetMember("us-east-1", "worker-3"),
                fleetMember("us-west-2"),
            ],
        });
        const { container } = render(<RegionBadgeList run={run} />);
        // Per UI-C1: just region name. No "(3)" or "(1)" counts.
        expect(container.textContent).not.toMatch(/\(\d+\)/);
    });

    it("falls back to originRegion when no fleet members exist", () => {
        const run = makeRun({ fleetMembers: [], originRegion: "us-east-1" });
        render(<RegionBadgeList run={run} />);
        expect(screen.getByText("us-east-1")).toBeInTheDocument();
    });

    it("renders an em-dash placeholder when there are zero regions", () => {
        const run = makeRun({ fleetMembers: [], originRegion: "" });
        render(<RegionBadgeList run={run} />);
        expect(screen.getByText("—")).toBeInTheDocument();
    });

    it("the wrapper carries an aria-label summarising all regions", () => {
        const run = makeRun({
            fleetMembers: [fleetMember("us-east-1"), fleetMember("us-west-2")],
        });
        render(<RegionBadgeList run={run} />);

        expect(screen.getByRole("list")).toHaveAttribute(
            "aria-label",
            "regions: us-east-1, us-west-2",
        );
    });

    describe("overflow at > MAX_VISIBLE", () => {
        const manyRegions = [
            "ap-south-1",
            "eu-central-1",
            "eu-west-1",
            "sa-east-1",
            "us-east-1",
            "us-east-2",
            "us-west-1",
            "us-west-2",
        ]; // 8 distinct regions, 3 over the cap

        it("shows the first MAX_VISIBLE pills + a `+N more` summary", () => {
            const run = makeRun({
                fleetMembers: manyRegions.map((r) => fleetMember(r)),
            });
            render(<RegionBadgeList run={run} />);

            // 5 region pills + 1 overflow pill = 6 list items
            const items = screen.getAllByRole("listitem");
            expect(items).toHaveLength(MAX_VISIBLE + 1);

            // Overflow pill text is "+N more"
            expect(screen.getByText(/^\+3 more$/)).toBeInTheDocument();
        });

        it("the overflow pill carries the hidden region names in title + aria-label", () => {
            const run = makeRun({
                fleetMembers: manyRegions.map((r) => fleetMember(r)),
            });
            render(<RegionBadgeList run={run} />);

            const overflow = screen.getByText(/^\+3 more$/);
            expect(overflow).toHaveAttribute("title", "us-east-2, us-west-1, us-west-2");
            expect(overflow).toHaveAttribute(
                "aria-label",
                "+3 more: us-east-2, us-west-1, us-west-2",
            );
        });

        it("respects a caller-supplied maxVisible override", () => {
            const run = makeRun({
                fleetMembers: manyRegions.map((r) => fleetMember(r)),
            });
            render(<RegionBadgeList run={run} maxVisible={2} />);

            // 2 visible + 1 overflow
            expect(screen.getAllByRole("listitem")).toHaveLength(3);
            expect(screen.getByText(/^\+6 more$/)).toBeInTheDocument();
        });
    });
});
