import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";

import {
  __resetPlatformCapabilitiesCache,
  usePlatformCapabilities,
} from "../usePlatformCapabilities";

function Probe() {
  const caps = usePlatformCapabilities();
  return (
    <div>
      <span data-testid="maxClusters">{caps.maxClustersPerGroup}</span>
      <span data-testid="loading">{String(caps.loading)}</span>
    </div>
  );
}

function stubCapabilities(body: unknown, ok = true) {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
    ok,
    status: ok ? 200 : 500,
    json: async () => body,
  }));
}

describe("usePlatformCapabilities", () => {
  beforeEach(() => __resetPlatformCapabilitiesCache());
  afterEach(() => vi.unstubAllGlobals());

  it("reports the per-group cluster limit", async () => {
    stubCapabilities({ maxClustersPerGroup: 3 });

    render(<Probe />);

    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));
    expect(screen.getByTestId("maxClusters")).toHaveTextContent("3");
  });

  it("a failing probe degrades to the default limit of 2 — the server still refuses what it must", async () => {
    stubCapabilities(undefined, false);

    render(<Probe />);

    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));
    expect(screen.getByTestId("maxClusters")).toHaveTextContent("2");
  });

  it("tolerates a body from an older backend that still sends the retired cluster list", async () => {
    stubCapabilities({ regions: ["lab"] });

    render(<Probe />);

    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));
    expect(screen.getByTestId("maxClusters")).toHaveTextContent("2");
  });
});
