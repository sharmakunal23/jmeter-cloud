import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { axe } from "vitest-axe";

import { CompareInsightsPanel } from "../CompareInsightsPanel";
import type { CompareInsights } from "../../api/ai";
import type { CompareInsightsStatus } from "../../hooks/useCompareInsights";

const onRegenerate = vi.fn();
const onClose = vi.fn();

function withData(): CompareInsights {
  return {
    runIds: ["01J0A", "01J0B"],
    model: "claude-test",
    promptVersion: "v1",
    summary: "Run B regressed average RT vs Run A.",
    findings: [
      { metric: "tps", verdict: "no significant change", delta: "+1.2%", detail: "Load matched.", evidence: "74.3 → 75.2/s" },
      { metric: "avgRtMs", verdict: "regression", delta: "+12.3%", detail: "Slower under the same load.", evidence: "187 → 210 ms" },
      { metric: "errorPct", verdict: "improvement", delta: "-0.4 pp", detail: "Fewer 5xx.", evidence: "0.8 → 0.4%" },
    ],
    tokensIn: 200,
    tokensOut: 60,
    cachedAt: "2026-05-31T00:00:00Z",
    fromCache: false,
  };
}

function renderPanel(status: CompareInsightsStatus, data: CompareInsights | null, ready = true) {
  return render(
    <CompareInsightsPanel
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

describe("CompareInsightsPanel (presentational side column)", () => {
  it("shows the delta's evidence and states both sides are read whole", () => {
    renderPanel({ kind: "ok" }, withData());
    expect(screen.getByText(/whole run on both sides/i)).toBeInTheDocument();
    expect(screen.getByText("187 → 210 ms")).toBeInTheDocument();
    expect(screen.getByText("0.8 → 0.4%")).toBeInTheDocument();
  });

  it("shows a 'not yet' hint when idle and both runs lack data", () => {
    renderPanel({ kind: "idle" }, null, false);
    expect(screen.getByText(/both runs have metrics/i)).toBeInTheDocument();
  });

  it("shows a loading message while comparing", () => {
    renderPanel({ kind: "loading" }, null);
    expect(screen.getByRole("status")).toHaveTextContent(/comparing the two runs/i);
  });

  it("renders verdict-coloured per-metric findings", () => {
    renderPanel({ kind: "ok" }, withData());
    expect(screen.getByText(/regressed average RT/)).toBeInTheDocument();
    expect(screen.getByText("regression")).toHaveClass("badge--err");
    expect(screen.getByText("improvement")).toHaveClass("badge--ok");
    expect(screen.getByText("no significant change")).toHaveClass("badge--info");
  });

  it("copies the comparison as Markdown, naming both runs in A/B order", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });

    renderPanel({ kind: "ok" }, withData());
    fireEvent.click(screen.getByRole("button", { name: /copy/i }));

    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1));
    const copied = writeText.mock.calls[0][0] as string;
    expect(copied).toContain("# AI comparison — run A 01J0A vs run B 01J0B");
    expect(copied).toContain("**avgRtMs — regression** (+12.3%)");
    expect(copied).toContain("Evidence: 187 → 210 ms");
    expect(copied).toContain("Advisory only");
    expect(await screen.findByRole("button", { name: /copied/i })).toBeInTheDocument();
  });

  it("Re-evaluate and close fire their callbacks", () => {
    renderPanel({ kind: "ok" }, withData());
    fireEvent.click(screen.getByRole("button", { name: /re-evaluate/i }));
    expect(onRegenerate).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: /close ai comparison insights/i }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("surfaces a friendly quota message on 429", () => {
    renderPanel(
      { kind: "error", message: "Daily AI limit reached — try again tomorrow.", quotaHit: true },
      null,
    );
    expect(screen.getByRole("alert")).toHaveTextContent(/daily ai limit/i);
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
