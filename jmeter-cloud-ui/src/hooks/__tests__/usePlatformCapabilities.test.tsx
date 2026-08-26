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
      <span data-testid="mode">{caps.provisioningMode}</span>
      <span data-testid="dynamic">{String(caps.dynamicScalingEnabled)}</span>
      <span data-testid="static">{String(caps.isStaticFleet)}</span>
      <span data-testid="regions">{caps.regions.join(",")}</span>
      <span data-testid="noun">{caps.regionNoun()}</span>
      <span data-testid="nounPlural">{caps.regionNoun({ plural: true, capitalize: true })}</span>
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

  it("reports the static posture and the data-center vocabulary", async () => {
    stubCapabilities({
      provisioningMode: "STATIC",
      dynamicScalingEnabled: false,
      podRecyclingEnabled: false,
      regions: ["na-east", "na-west"],
      regionLabel: "dataCenter",
    });

    render(<Probe />);

    await waitFor(() => expect(screen.getByTestId("mode")).toHaveTextContent("STATIC"));
    expect(screen.getByTestId("dynamic")).toHaveTextContent("false");
    expect(screen.getByTestId("static")).toHaveTextContent("true");
    expect(screen.getByTestId("regions")).toHaveTextContent("na-east,na-west");
    expect(screen.getByTestId("noun")).toHaveTextContent("data center");
    expect(screen.getByTestId("nounPlural")).toHaveTextContent("Data centers");
  });

  it("a failing probe degrades to the historical everything-visible posture — a stale browser "
     + "may show a Spin button, but the server still refuses it", async () => {
    stubCapabilities(undefined, false);

    render(<Probe />);

    await waitFor(() => expect(screen.getByTestId("dynamic")).toHaveTextContent("true"));
    expect(screen.getByTestId("mode")).toHaveTextContent("DYNAMIC");
    expect(screen.getByTestId("noun")).toHaveTextContent("region");
  });

  it("tolerates a backend that hasn't shipped every field yet", async () => {
    stubCapabilities({ provisioningMode: "STATIC", dynamicScalingEnabled: false });

    render(<Probe />);

    await waitFor(() => expect(screen.getByTestId("static")).toHaveTextContent("true"));
    expect(screen.getByTestId("regions")).toHaveTextContent("");
    expect(screen.getByTestId("noun")).toHaveTextContent("region");
  });

  it("probes once for the whole session no matter how many consumers mount", async () => {
    stubCapabilities({
      provisioningMode: "STATIC", dynamicScalingEnabled: false,
      podRecyclingEnabled: false, regions: [], regionLabel: "dataCenter",
    });

    render(<><Probe /><Probe /><Probe /></>);

    await waitFor(() =>
      expect(screen.getAllByTestId("mode")[0]).toHaveTextContent("STATIC"));
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
