import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";

const mocks = vi.hoisted(() => ({
  timeseriesBatch: vi.fn(),
}));

vi.mock("../../api/runs", () => ({
  runsApi: { timeseriesBatch: mocks.timeseriesBatch },
}));

import { useTwoRunTimeseries, POLL_INTERVAL_MS } from "../useTwoRunTimeseries";
import type { MetricsTimeseries, MetricsTimeseriesBatch } from "../../api/runs";

function sampleSeries(runId: string, fromSec: number, tpsBase: number): MetricsTimeseries {
  return {
    runId,
    bucketSize: 1,
    fromSecond: fromSec,
    toSecond:   fromSec + 2,
    series: {
      tps:      [{ sec: fromSec, v: tpsBase }, { sec: fromSec + 1, v: tpsBase + 1 }],
      avgRtMs:  [{ sec: fromSec, v: 12 },     { sec: fromSec + 1, v: 14 }],
      errorPct: [{ sec: fromSec, v: 0 },      { sec: fromSec + 1, v: 0 }],
      statusCodes: { "200": [{ sec: fromSec, v: tpsBase }] },
    },
  };
}

function sampleBatch(idA: string, idB: string): MetricsTimeseriesBatch {
  return {
    runs: {
      [idA]: sampleSeries(idA, 1000, 5),
      [idB]: sampleSeries(idB, 2000, 8),
    },
    missing: [],
  };
}

beforeEach(() => {
  mocks.timeseriesBatch.mockReset();
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useTwoRunTimeseries", () => {
  it("fires an initial fetch on mount + populates data on success", async () => {
    mocks.timeseriesBatch.mockResolvedValueOnce(sampleBatch("A", "B"));
    const { result } = renderHook(() => useTwoRunTimeseries("A", "B", "RUNNING", "RUNNING"));

    expect(mocks.timeseriesBatch).toHaveBeenCalledTimes(1);
    expect(mocks.timeseriesBatch).toHaveBeenLastCalledWith("A", "B", expect.anything());
    await waitFor(() => expect(result.current.status.kind).toBe("ok"));
    expect(Object.keys(result.current.data?.runs ?? {})).toEqual(["A", "B"]);
    expect(result.current.lastUpdated).toBeInstanceOf(Date);
  });

  it("preserves last-good data when a subsequent poll rejects", async () => {
    mocks.timeseriesBatch.mockResolvedValueOnce(sampleBatch("A", "B"));
    const { result } = renderHook(() => useTwoRunTimeseries("A", "B", "RUNNING", "RUNNING"));
    await waitFor(() => expect(result.current.status.kind).toBe("ok"));

    mocks.timeseriesBatch.mockRejectedValueOnce(new Error("network down"));
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS); });
    await waitFor(() => expect(result.current.status.kind).toBe("error"));
    expect((result.current.status as { kind: "error"; message: string }).message).toBe("network down");
    // Prior snapshot intact — charts don't blank between polls.
    expect(result.current.data?.runs["A"]?.runId).toBe("A");
  });

  it("polls every POLL_INTERVAL_MS while at least one run is non-terminal", async () => {
    mocks.timeseriesBatch.mockResolvedValue(sampleBatch("A", "B"));
    // A terminal, B still running → should still poll.
    renderHook(() => useTwoRunTimeseries("A", "B", "COMPLETED", "RUNNING"));

    expect(mocks.timeseriesBatch).toHaveBeenCalledTimes(1);
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS); });
    await waitFor(() => expect(mocks.timeseriesBatch).toHaveBeenCalledTimes(2));
  });

  it("stops polling once BOTH runs are terminal", () => {
    mocks.timeseriesBatch.mockResolvedValue(sampleBatch("A", "B"));
    const { result } = renderHook(() => useTwoRunTimeseries("A", "B", "COMPLETED", "FAILED"));

    expect(mocks.timeseriesBatch).toHaveBeenCalledTimes(1);
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS * 5); });
    expect(mocks.timeseriesBatch).toHaveBeenCalledTimes(1);
    expect(result.current.isPaused).toBe(true);
    expect(result.current.pauseReason).toBe("delayNull");
  });

  it.each([
    ["FAILED",    "ABORTED"],
    ["COMPLETED", "ABORTED"],
    ["ABORTED",   "ABORTED"],
  ] as const)("treats (%s, %s) as both-terminal", (a, b) => {
    mocks.timeseriesBatch.mockResolvedValue(sampleBatch("A", "B"));
    renderHook(() => useTwoRunTimeseries("A", "B", a, b));
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS * 3); });
    expect(mocks.timeseriesBatch).toHaveBeenCalledTimes(1);
  });

  it("re-fetches when either id changes (user picks a different run)", async () => {
    mocks.timeseriesBatch.mockResolvedValue(sampleBatch("A", "B"));
    const { rerender } = renderHook(
      ({ idA, idB }: { idA: string; idB: string }) =>
        useTwoRunTimeseries(idA, idB, "RUNNING", "RUNNING"),
      { initialProps: { idA: "A", idB: "B" } },
    );
    expect(mocks.timeseriesBatch).toHaveBeenCalledTimes(1);
    expect(mocks.timeseriesBatch).toHaveBeenLastCalledWith("A", "B", expect.anything());

    rerender({ idA: "A", idB: "C" });
    expect(mocks.timeseriesBatch).toHaveBeenCalledTimes(2);
    expect(mocks.timeseriesBatch).toHaveBeenLastCalledWith("A", "C", expect.anything());

    rerender({ idA: "X", idB: "C" });
    expect(mocks.timeseriesBatch).toHaveBeenCalledTimes(3);
    expect(mocks.timeseriesBatch).toHaveBeenLastCalledWith("X", "C", expect.anything());
  });

  it("does NOT fetch when either id is empty (guards against transient page state)", () => {
    mocks.timeseriesBatch.mockResolvedValue(sampleBatch("A", "B"));
    const { rerender } = renderHook(
      ({ idA, idB }: { idA: string; idB: string }) =>
        useTwoRunTimeseries(idA, idB, "RUNNING", "RUNNING"),
      { initialProps: { idA: "", idB: "B" } },
    );
    expect(mocks.timeseriesBatch).not.toHaveBeenCalled();
    rerender({ idA: "A", idB: "" });
    expect(mocks.timeseriesBatch).not.toHaveBeenCalled();
  });
});
