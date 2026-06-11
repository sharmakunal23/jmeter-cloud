import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
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
      { metric: "tps", verdict: "no significant change", delta: "+1.2%" },
      { metric: "avgRtMs", verdict: "regression", delta: "+12.3%" },
      { metric: "errorPct", verdict: "improvement", delta: "-0.4 pp" },
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
