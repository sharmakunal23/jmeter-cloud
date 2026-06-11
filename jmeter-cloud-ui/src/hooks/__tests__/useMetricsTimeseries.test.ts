import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";

// Mock the runs API surface so the hook's network call returns a
// known shape. Hoisted via vi.hoisted so the mock factory can refer to
// it without TDZ errors after vitest hoists vi.mock to the top.
const mocks = vi.hoisted(() => ({
  timeseries: vi.fn(),
}));

vi.mock("../../api/runs", () => ({
  runsApi: { timeseries: mocks.timeseries },
}));

import { useMetricsTimeseries, POLL_INTERVAL_MS } from "../useMetricsTimeseries";
import type { MetricsTimeseries } from "../../api/runs";

function sample(): MetricsTimeseries {
  return {
    runId: "run-1",
    bucketSize: 1,
    fromSecond: 100,
    toSecond:   102,
    series: {
      tps:      [{ sec: 100, v: 5 }, { sec: 101, v: 6 }],
      avgRtMs:  [{ sec: 100, v: 12 }, { sec: 101, v: 14 }],
      errorPct: [{ sec: 100, v: 0 }, { sec: 101, v: 0 }],
      statusCodes: { "200": [{ sec: 100, v: 5 }] },
    },
  };
}

beforeEach(() => {
  mocks.timeseries.mockReset();
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useMetricsTimeseries", () => {
  it("fires an initial fetch on mount + populates data on success", async () => {
    mocks.timeseries.mockResolvedValueOnce(sample());
    const { result } = renderHook(() => useMetricsTimeseries("run-1", "RUNNING"));

    expect(mocks.timeseries).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(result.current.status.kind).toBe("ok"));
    expect(result.current.data?.runId).toBe("run-1");
    expect(result.current.lastUpdated).toBeInstanceOf(Date);
  });

  it("surfaces error state when the next poll rejects, and keeps prior data populated", async () => {
    // First fetch (mount) succeeds; the next poll fires after
    // POLL_INTERVAL_MS and rejects. The hook should keep the prior
    // snapshot in `data` and flip status to error.
    mocks.timeseries.mockResolvedValueOnce(sample());
    const { result } = renderHook(() => useMetricsTimeseries("run-1", "RUNNING"));
    await waitFor(() => expect(result.current.status.kind).toBe("ok"));
    expect(result.current.data?.runId).toBe("run-1");

    mocks.timeseries.mockRejectedValueOnce(new Error("kaboom"));
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS); });
    await waitFor(() => expect(result.current.status.kind).toBe("error"));
    expect((result.current.status as { kind: "error"; message: string }).message).toBe("kaboom");
    expect(result.current.data?.runId).toBe("run-1"); // last good snapshot preserved
  });

  it("polls every POLL_INTERVAL_MS while runState is non-terminal", async () => {
    mocks.timeseries.mockResolvedValue(sample());
    renderHook(() => useMetricsTimeseries("run-1", "RUNNING"));

    // Initial fetch.
    expect(mocks.timeseries).toHaveBeenCalledTimes(1);

    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS); });
    await waitFor(() => expect(mocks.timeseries).toHaveBeenCalledTimes(2));

    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS * 2); });
    await waitFor(() => expect(mocks.timeseries).toHaveBeenCalledTimes(4));
  });

  it("does NOT poll when runState is terminal — initial fetch only", async () => {
    mocks.timeseries.mockResolvedValue(sample());
    const { result } = renderHook(() => useMetricsTimeseries("run-1", "COMPLETED"));

    expect(mocks.timeseries).toHaveBeenCalledTimes(1);
    act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS * 5); });
    expect(mocks.timeseries).toHaveBeenCalledTimes(1);
    expect(result.current.isPaused).toBe(true);
    expect(result.current.pauseReason).toBe("delayNull");
  });

  it.each(["FAILED", "ABORTED"] as const)(
    "stops polling for terminal state %s",
    (state) => {
      mocks.timeseries.mockResolvedValue(sample());
      renderHook(() => useMetricsTimeseries("run-1", state));
      act(() => { vi.advanceTimersByTime(POLL_INTERVAL_MS * 3); });
      expect(mocks.timeseries).toHaveBeenCalledTimes(1);
    },
  );

  it("re-fetches when the runId changes", async () => {
    mocks.timeseries.mockResolvedValue(sample());
    const { rerender } = renderHook(
      ({ runId }: { runId: string }) => useMetricsTimeseries(runId, "RUNNING"),
      { initialProps: { runId: "run-A" } },
    );
    expect(mocks.timeseries).toHaveBeenCalledTimes(1);
    expect(mocks.timeseries).toHaveBeenLastCalledWith("run-A", expect.anything(), { byRegion: false, window: "all" });

    rerender({ runId: "run-B" });
    expect(mocks.timeseries).toHaveBeenCalledTimes(2);
    expect(mocks.timeseries).toHaveBeenLastCalledWith("run-B", expect.anything(), { byRegion: false, window: "all" });
  });

  it("re-fetches with byRegion=true when the flag flips", async () => {
    mocks.timeseries.mockResolvedValue(sample());
    const { rerender } = renderHook(
      ({ byRegion }: { byRegion: boolean }) => useMetricsTimeseries("run-A", "RUNNING", byRegion),
      { initialProps: { byRegion: false } },
    );
    expect(mocks.timeseries).toHaveBeenLastCalledWith("run-A", expect.anything(), { byRegion: false, window: "all" });

    rerender({ byRegion: true });
    expect(mocks.timeseries).toHaveBeenLastCalledWith("run-A", expect.anything(), { byRegion: true, window: "all" });
  });

  it("re-fetches with the selected window when it changes", async () => {
    mocks.timeseries.mockResolvedValue(sample());
    const { rerender } = renderHook(
      ({ window }: { window: "all" | "30m" }) => useMetricsTimeseries("run-A", "RUNNING", false, window),
      { initialProps: { window: "all" as "all" | "30m" } },
    );
    expect(mocks.timeseries).toHaveBeenLastCalledWith("run-A", expect.anything(), { byRegion: false, window: "all" });

    rerender({ window: "30m" });
    expect(mocks.timeseries).toHaveBeenLastCalledWith("run-A", expect.anything(), { byRegion: false, window: "30m" });
  });
});
