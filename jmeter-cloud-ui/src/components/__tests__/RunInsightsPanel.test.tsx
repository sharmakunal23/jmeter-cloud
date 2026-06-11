import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { axe } from "vitest-axe";

import { RunInsightsPanel } from "../RunInsightsPanel";
import type { RunInsights } from "../../api/ai";
import type { RunInsightsStatus } from "../../hooks/useRunInsights";

const onRegenerate = vi.fn();
const onClose = vi.fn();

function withData(overrides: Partial<RunInsights> = {}): RunInsights {
  return {
    runId: "01J0RUN",
    model: "claude-test",
    promptVersion: "v1",
    summary: "Sustained ~75 RPS; p99 climbed late.",
    findings: [
      { severity: "info", title: "Steady throughput", detail: "TPS held flat." },
      { severity: "warn", title: "Latency tail", detail: "p99 climbed late." },
      { severity: "crit", title: "Error spike", detail: "5xx at +90s." },
    ],
    tokensIn: 100,
    tokensOut: 50,
    cachedAt: "2026-05-31T00:00:00Z",
    fromCache: false,
    ...overrides,
  };
}

function renderPanel(status: RunInsightsStatus, data: RunInsights | null, ready = true) {
  return render(
    <RunInsightsPanel
      status={status}
      data={data}
      ready={ready}
      onRegenerate={onRegenerate}
      onClose={onClose}
    />,
  );
}

beforeEach(() => {
  onRegenerate.mockReset();
  onClose.mockReset();
});

describe("RunInsightsPanel (presentational side column)", () => {
  it("shows a 'not yet' hint when idle and not enough data", () => {
    renderPanel({ kind: "idle" }, null, false);
    expect(screen.getByText(/~30 s of metrics/i)).toBeInTheDocument();
  });

  it("shows a loading message while analyzing", () => {
    renderPanel({ kind: "loading" }, null);
    expect(screen.getByRole("status")).toHaveTextContent(/claude is reading/i);
  });

  it("renders summary, severity-coloured findings, meta + disclaimer", () => {
    renderPanel({ kind: "ok" }, withData());
    expect(screen.getByText(/Sustained ~75 RPS/)).toBeInTheDocument();
    expect(screen.getByText("Steady throughput")).toBeInTheDocument();
    expect(screen.getByText("crit")).toHaveClass("badge--err");
    expect(screen.getByText("warn")).toHaveClass("badge--warn");
    expect(screen.getByText("info")).toHaveClass("badge--info");
    expect(screen.getByText(/Claude can be wrong/i)).toBeInTheDocument();
  });

  it("Re-evaluate and close fire their callbacks", () => {
    renderPanel({ kind: "ok" }, withData());
    fireEvent.click(screen.getByRole("button", { name: /re-evaluate/i }));
    expect(onRegenerate).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: /close ai insights/i }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("surfaces a friendly quota message on 429", () => {
    renderPanel(
      { kind: "error", message: "Daily AI limit reached — try again tomorrow.", quotaHit: true },
      null,
    );
    expect(screen.getByRole("alert")).toHaveTextContent(/daily ai limit/i);
  });

  it("notes cache vs fresh provenance", () => {
    renderPanel({ kind: "ok" }, withData({ fromCache: true }));
    expect(screen.getByText(/cached/)).toBeInTheDocument();
  });

  it("is axe-clean in hint, loading, and populated states", async () => {
    const hint = renderPanel({ kind: "idle" }, null, false);
    expect(await axe(hint.container)).toHaveNoViolations();
    hint.unmount();

    const loading = renderPanel({ kind: "loading" }, null);
    expect(await axe(loading.container)).toHaveNoViolations();
    loading.unmount();

    const ok = renderPanel({ kind: "ok" }, withData());
    expect(await axe(ok.container)).toHaveNoViolations();
  });
});
