import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";

const mocks = vi.hoisted(() => ({
  runInsights: vi.fn(),
}));

vi.mock("../../api/ai", () => ({
  aiApi: { runInsights: mocks.runInsights },
}));

import { useRunInsights } from "../useRunInsights";
import { GlobalOrchestratorError } from "../../api/runs";
import type { RunInsights } from "../../api/ai";

function sample(fromCache: boolean): RunInsights {
  return {
    runId: "01J0RUN",
    model: "claude-test",
    promptVersion: "v1",
    summary: "Steady run.",
    findings: [{ severity: "warn", title: "Latency tail", detail: "p99 climbed." }],
    tokensIn: 100,
    tokensOut: 50,
    cachedAt: "2026-05-31T00:00:00Z",
    fromCache,
  };
}

beforeEach(() => {
  mocks.runInsights.mockReset();
});

describe("useRunInsights", () => {
  it("generate() transitions loading → ok and populates data", async () => {
    mocks.runInsights.mockResolvedValue(sample(false));
    const { result } = renderHook(() => useRunInsights("01J0RUN"));
    expect(result.current.status.kind).toBe("idle");

    act(() => result.current.generate());
    await waitFor(() => expect(result.current.status.kind).toBe("ok"));
    expect(result.current.data?.summary).toBe("Steady run.");
    expect(mocks.runInsights).toHaveBeenCalledWith("01J0RUN", { fresh: false }, expect.anything());
  });

  it("surfaces a cache hit (fromCache=true)", async () => {
    mocks.runInsights.mockResolvedValue(sample(true));
    const { result } = renderHook(() => useRunInsights("01J0RUN"));
    act(() => result.current.generate());
    await waitFor(() => expect(result.current.status.kind).toBe("ok"));
    expect(result.current.data?.fromCache).toBe(true);
  });

  it("regenerate() sends fresh=true to bypass the cache", async () => {
    mocks.runInsights.mockResolvedValue(sample(false));
    const { result } = renderHook(() => useRunInsights("01J0RUN"));
    act(() => result.current.regenerate());
    await waitFor(() => expect(result.current.status.kind).toBe("ok"));
    expect(mocks.runInsights).toHaveBeenCalledWith("01J0RUN", { fresh: true }, expect.anything());
  });

  it("maps a generic failure to an error state", async () => {
    mocks.runInsights.mockRejectedValue(new Error("boom"));
    const { result } = renderHook(() => useRunInsights("01J0RUN"));
    act(() => result.current.generate());
    await waitFor(() => expect(result.current.status.kind).toBe("error"));
    if (result.current.status.kind === "error") {
      expect(result.current.status.quotaHit).toBe(false);
      expect(result.current.status.message).toContain("boom");
    }
  });

  it("maps a 429 to a friendly quota message (quotaHit=true)", async () => {
    mocks.runInsights.mockRejectedValue(
      new GlobalOrchestratorError(429, "AI_QUOTA_EXCEEDED", "cap reached"),
    );
    const { result } = renderHook(() => useRunInsights("01J0RUN"));
    act(() => result.current.generate());
    await waitFor(() => expect(result.current.status.kind).toBe("error"));
    if (result.current.status.kind === "error") {
      expect(result.current.status.quotaHit).toBe(true);
      expect(result.current.status.message).toMatch(/daily ai limit/i);
    }
  });
});
