import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";

import { usePanelQuery } from "../usePanelQuery";
import { useRefreshTick } from "../useRefreshTick";

beforeEach(() => { vi.useFakeTimers({ shouldAdvanceTime: true }); });
afterEach(() => { vi.useRealTimers(); });

describe("usePanelQuery", () => {
  it("fetches on mount, again when a dep or the tick changes, and keeps the last good data across an error", async () => {
    const fetcher = vi.fn().mockResolvedValue({ n: 1 });
    const { result, rerender } = renderHook(
      ({ dep, tick }: { dep: string; tick: number }) => usePanelQuery(fetcher, [dep], tick),
      { initialProps: { dep: "a", tick: 0 } },
    );
    expect(result.current.status.kind).toBe("loading");
    await waitFor(() => expect(result.current.status.kind).toBe("ok"));
    expect(result.current.data).toEqual({ n: 1 });
    expect(fetcher).toHaveBeenCalledTimes(1);

    fetcher.mockResolvedValue({ n: 2 });
    rerender({ dep: "b", tick: 0 });
    await waitFor(() => expect(result.current.data).toEqual({ n: 2 }));

    fetcher.mockRejectedValue(new Error("nope"));
    rerender({ dep: "b", tick: 1 });
    await waitFor(() => expect(result.current.status).toEqual({ kind: "error", message: "nope" }));
    expect(result.current.data).toEqual({ n: 2 });
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it("does nothing while disabled — a collapsed section costs no request — and fetches once enabled", async () => {
    const fetcher = vi.fn().mockResolvedValue("x");
    const { result, rerender } = renderHook(
      ({ enabled }: { enabled: boolean }) => usePanelQuery(fetcher, [], 0, enabled),
      { initialProps: { enabled: false } },
    );
    expect(fetcher).not.toHaveBeenCalled();
    expect(result.current.data).toBeNull();
    rerender({ enabled: true });
    await waitFor(() => expect(result.current.data).toBe("x"));
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it("aborts the in-flight request when the inputs change and ignores its outcome", async () => {
    const seen: AbortSignal[] = [];
    const fetcher = vi.fn((signal: AbortSignal) => { seen.push(signal); return new Promise<string>(() => { /* never */ }); });
    const { rerender } = renderHook(({ dep }: { dep: string }) => usePanelQuery(fetcher, [dep], 0), { initialProps: { dep: "a" } });
    rerender({ dep: "b" });
    expect(seen[0]!.aborted).toBe(true);
    expect(seen[1]!.aborted).toBe(false);
  });
});

describe("useRefreshTick", () => {
  it("advances on the interval while visible, never for a null interval, and on demand", async () => {
    const { result } = renderHook(() => useRefreshTick(1_000));
    expect(result.current.tick).toBe(0);
    expect(result.current.isPaused).toBe(false);
    await act(async () => { vi.advanceTimersByTime(1_050); });
    expect(result.current.tick).toBe(1);
    act(() => result.current.refresh());
    expect(result.current.tick).toBe(2);

    const terminal = renderHook(() => useRefreshTick(null));
    expect(terminal.result.current.isPaused).toBe(true);
    expect(terminal.result.current.pauseReason).toBe("delayNull");
    await act(async () => { vi.advanceTimersByTime(5_000); });
    expect(terminal.result.current.tick).toBe(0);
  });
});
