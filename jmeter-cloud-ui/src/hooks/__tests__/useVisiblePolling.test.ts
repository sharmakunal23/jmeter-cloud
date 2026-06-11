import { renderHook, act } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createRef } from "react";

import { useVisiblePolling } from "../useVisiblePolling";

// ── Test doubles for browser APIs jsdom doesn't provide / expose ─────────

let ioCallback: IntersectionObserverCallback | null = null;
let ioObserveCalls = 0;
let ioDisconnectCalls = 0;

class FakeIntersectionObserver {
  constructor(cb: IntersectionObserverCallback) {
    ioCallback = cb;
  }
  observe = () => {
    ioObserveCalls++;
  };
  unobserve = () => {};
  disconnect = () => {
    ioDisconnectCalls++;
  };
  takeRecords = () => [];
  root = null;
  rootMargin = "0px";
  thresholds = [0];
}

/** Set document.visibilityState and fire the matching event. */
function setVisibility(state: "visible" | "hidden") {
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    get: () => state,
  });
  Object.defineProperty(document, "hidden", {
    configurable: true,
    get: () => state === "hidden",
  });
  document.dispatchEvent(new Event("visibilitychange"));
}

/** Fire the IntersectionObserver callback the hook handed FakeIntersectionObserver. */
function fireIntersection(isIntersecting: boolean) {
  expect(ioCallback).not.toBeNull();
  ioCallback!(
    [{ isIntersecting } as unknown as IntersectionObserverEntry],
    {} as IntersectionObserver,
  );
}

beforeEach(() => {
  ioCallback = null;
  ioObserveCalls = 0;
  ioDisconnectCalls = 0;
  vi.stubGlobal("IntersectionObserver", FakeIntersectionObserver);
  setVisibility("visible");
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("useVisiblePolling — happy path", () => {
  it("fires the callback every delayMs while every gate is open", () => {
    const cb = vi.fn();
    renderHook(() => useVisiblePolling(cb, 1000));

    expect(cb).not.toHaveBeenCalled(); // No immediate fire — caller owns the initial load.

    act(() => { vi.advanceTimersByTime(1000); });
    expect(cb).toHaveBeenCalledTimes(1);

    act(() => { vi.advanceTimersByTime(3000); });
    expect(cb).toHaveBeenCalledTimes(4);
  });

  it("does NOT fire synchronously on mount — initial fetches are the caller's responsibility", () => {
    const cb = vi.fn();
    renderHook(() => useVisiblePolling(cb, 500));
    expect(cb).not.toHaveBeenCalled();
  });

  it("uses the latest callback (not a stale closure) when the consumer re-renders", () => {
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = renderHook(
      ({ cb }: { cb: () => void }) => useVisiblePolling(cb, 1000),
      { initialProps: { cb: first } },
    );

    rerender({ cb: second });
    act(() => { vi.advanceTimersByTime(1000); });

    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });
});

describe("useVisiblePolling — gate: delayMs === null", () => {
  it("does not start a timer when delayMs is null", () => {
    const cb = vi.fn();
    const { result } = renderHook(() => useVisiblePolling(cb, null));

    act(() => { vi.advanceTimersByTime(10_000); });
    expect(cb).not.toHaveBeenCalled();
    expect(result.current).toEqual({ isPaused: true, pauseReason: "delayNull" });
  });

  it("stops firing the moment delayMs flips to null mid-flight", () => {
    const cb = vi.fn();
    const { rerender } = renderHook(
      ({ delay }: { delay: number | null }) => useVisiblePolling(cb, delay),
      { initialProps: { delay: 500 as number | null } },
    );

    act(() => { vi.advanceTimersByTime(500); });
    expect(cb).toHaveBeenCalledTimes(1);

    rerender({ delay: null });
    act(() => { vi.advanceTimersByTime(5_000); });
    expect(cb).toHaveBeenCalledTimes(1);
  });

  it("resumes from the new delay after delayMs flips back to a number", () => {
    const cb = vi.fn();
    const { rerender } = renderHook(
      ({ delay }: { delay: number | null }) => useVisiblePolling(cb, delay),
      { initialProps: { delay: null as number | null } },
    );

    act(() => { vi.advanceTimersByTime(5_000); });
    expect(cb).not.toHaveBeenCalled();

    rerender({ delay: 200 });
    act(() => { vi.advanceTimersByTime(600); });
    expect(cb).toHaveBeenCalledTimes(3);
  });
});

describe("useVisiblePolling — gate: opts.paused", () => {
  it("does not fire while paused=true", () => {
    const cb = vi.fn();
    const { result } = renderHook(() => useVisiblePolling(cb, 500, { paused: true }));

    act(() => { vi.advanceTimersByTime(2_000); });
    expect(cb).not.toHaveBeenCalled();
    expect(result.current.pauseReason).toBe("manual");
  });

  it("resumes immediately after paused flips back to false", () => {
    const cb = vi.fn();
    const { rerender } = renderHook(
      ({ paused }: { paused: boolean }) => useVisiblePolling(cb, 500, { paused }),
      { initialProps: { paused: true } },
    );

    act(() => { vi.advanceTimersByTime(2_000); });
    expect(cb).not.toHaveBeenCalled();

    rerender({ paused: false });
    act(() => { vi.advanceTimersByTime(500); });
    expect(cb).toHaveBeenCalledTimes(1);
  });
});

describe("useVisiblePolling — gate: document.visibilityState", () => {
  it("stops firing when the browser tab goes hidden, resumes when it comes back", () => {
    const cb = vi.fn();
    const { result } = renderHook(() => useVisiblePolling(cb, 1000));

    act(() => { vi.advanceTimersByTime(1000); });
    expect(cb).toHaveBeenCalledTimes(1);

    act(() => { setVisibility("hidden"); });
    expect(result.current).toEqual({ isPaused: true, pauseReason: "documentHidden" });

    act(() => { vi.advanceTimersByTime(5_000); });
    expect(cb).toHaveBeenCalledTimes(1); // stuck at 1 while hidden

    act(() => { setVisibility("visible"); });
    expect(result.current).toEqual({ isPaused: false, pauseReason: null });

    act(() => { vi.advanceTimersByTime(1000); });
    expect(cb).toHaveBeenCalledTimes(2);
  });

  it("starts paused when the page is hidden at mount", () => {
    setVisibility("hidden");
    const cb = vi.fn();
    const { result } = renderHook(() => useVisiblePolling(cb, 500));

    act(() => { vi.advanceTimersByTime(2_000); });
    expect(cb).not.toHaveBeenCalled();
    expect(result.current.pauseReason).toBe("documentHidden");
  });
});

describe("useVisiblePolling — gate: IntersectionObserver", () => {
  it("only attaches an observer when targetRef is provided", () => {
    const cb = vi.fn();
    renderHook(() => useVisiblePolling(cb, 500));
    expect(ioObserveCalls).toBe(0);
  });

  it("attaches an observer to targetRef.current and reports observe() being called", () => {
    const cb = vi.fn();
    const ref = createRef<HTMLDivElement>();
    Object.defineProperty(ref, "current", { value: document.createElement("div") });

    renderHook(() => useVisiblePolling(cb, 500, { targetRef: ref }));
    expect(ioObserveCalls).toBe(1);
  });

  it("pauses when the element scrolls off-screen, resumes when it scrolls back", () => {
    const cb = vi.fn();
    const ref = createRef<HTMLDivElement>();
    Object.defineProperty(ref, "current", { value: document.createElement("div") });

    const { result } = renderHook(() => useVisiblePolling(cb, 500, { targetRef: ref }));

    // Default state assumes intersecting until the IO callback says otherwise.
    act(() => { vi.advanceTimersByTime(500); });
    expect(cb).toHaveBeenCalledTimes(1);

    act(() => { fireIntersection(false); });
    expect(result.current).toEqual({ isPaused: true, pauseReason: "offscreen" });
    act(() => { vi.advanceTimersByTime(5_000); });
    expect(cb).toHaveBeenCalledTimes(1);

    act(() => { fireIntersection(true); });
    expect(result.current).toEqual({ isPaused: false, pauseReason: null });
    act(() => { vi.advanceTimersByTime(500); });
    expect(cb).toHaveBeenCalledTimes(2);
  });

  it("disconnects the observer on unmount — no leak if 100 panels mount/unmount in a session", () => {
    const cb = vi.fn();
    const ref = createRef<HTMLDivElement>();
    Object.defineProperty(ref, "current", { value: document.createElement("div") });

    const { unmount } = renderHook(() => useVisiblePolling(cb, 500, { targetRef: ref }));
    unmount();

    expect(ioDisconnectCalls).toBe(1);
  });
});

describe("useVisiblePolling — pauseReason precedence", () => {
  it("manual beats every other gate (including delayNull, hidden, offscreen)", () => {
    setVisibility("hidden");
    const ref = createRef<HTMLDivElement>();
    Object.defineProperty(ref, "current", { value: document.createElement("div") });
    const { result } = renderHook(() =>
      useVisiblePolling(vi.fn(), null, { paused: true, targetRef: ref }),
    );
    act(() => { fireIntersection(false); });
    expect(result.current.pauseReason).toBe("manual");
  });

  it("delayNull beats documentHidden + offscreen when paused is false", () => {
    setVisibility("hidden");
    const ref = createRef<HTMLDivElement>();
    Object.defineProperty(ref, "current", { value: document.createElement("div") });
    const { result } = renderHook(() =>
      useVisiblePolling(vi.fn(), null, { targetRef: ref }),
    );
    act(() => { fireIntersection(false); });
    expect(result.current.pauseReason).toBe("delayNull");
  });

  it("documentHidden beats offscreen when both fire", () => {
    setVisibility("hidden");
    const ref = createRef<HTMLDivElement>();
    Object.defineProperty(ref, "current", { value: document.createElement("div") });
    const { result } = renderHook(() =>
      useVisiblePolling(vi.fn(), 500, { targetRef: ref }),
    );
    act(() => { fireIntersection(false); });
    expect(result.current.pauseReason).toBe("documentHidden");
  });

  it("returns null pauseReason when every gate is open", () => {
    const { result } = renderHook(() => useVisiblePolling(vi.fn(), 500));
    expect(result.current).toEqual({ isPaused: false, pauseReason: null });
  });
});

describe("useVisiblePolling — cleanup", () => {
  it("removes the visibilitychange listener on unmount — no late callbacks after the consumer is gone", () => {
    const cb = vi.fn();
    const { unmount } = renderHook(() => useVisiblePolling(cb, 500));
    unmount();

    // After unmount, flipping visibility must not affect anything.
    setVisibility("hidden");
    setVisibility("visible");
    act(() => { vi.advanceTimersByTime(5_000); });
    expect(cb).not.toHaveBeenCalled();
  });
});
