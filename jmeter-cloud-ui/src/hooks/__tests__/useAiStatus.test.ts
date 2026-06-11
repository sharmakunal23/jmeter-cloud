import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";

const mocks = vi.hoisted(() => ({
  status: vi.fn(),
}));

vi.mock("../../api/ai", () => ({
  aiApi: { status: mocks.status },
}));

import { useAiStatus, __resetAiStatusCache } from "../useAiStatus";

beforeEach(() => {
  mocks.status.mockReset();
  __resetAiStatusCache();
});

afterEach(() => {
  __resetAiStatusCache();
});

describe("useAiStatus", () => {
  it("resolves enabled + model when the server reports a key", async () => {
    mocks.status.mockResolvedValue({ enabled: true, model: "claude-sonnet-4-6" });
    const { result } = renderHook(() => useAiStatus());
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.enabled).toBe(true);
    expect(result.current.model).toBe("claude-sonnet-4-6");
  });

  it("falls back to disabled when the probe rejects", async () => {
    mocks.status.mockRejectedValue(new Error("network"));
    const { result } = renderHook(() => useAiStatus());
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.enabled).toBe(false);
  });

  it("memoizes — a second mount does not refetch", async () => {
    mocks.status.mockResolvedValue({ enabled: true, model: "m" });
    const first = renderHook(() => useAiStatus());
    await waitFor(() => expect(first.result.current.loading).toBe(false));
    renderHook(() => useAiStatus());
    await waitFor(() => expect(mocks.status).toHaveBeenCalledTimes(1));
  });
});
