import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { DynamicScalingRoute } from "../components/DynamicScalingRoute";
import { Layout } from "../components/Layout";
import { __resetPlatformCapabilitiesCache } from "../hooks/usePlatformCapabilities";

/**
 * The UI reflects the server's provisioning posture.
 *
 * <p>These assert the *reflection*, not the enforcement: the server refuses
 * spin / restart / declare on its own, so a stale browser showing a button
 * is a cosmetic problem, not a security one. What matters here is that an
 * operator on a static fleet is never offered a control that cannot work,
 * and that a bookmarked Capacity URL lands somewhere useful instead of on a
 * 404 that reads as "the platform is broken".
 */

function stubCapabilities(dynamicScalingEnabled: boolean) {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => ({
      provisioningMode: dynamicScalingEnabled ? "DYNAMIC" : "STATIC",
      dynamicScalingEnabled,
      podRecyclingEnabled: dynamicScalingEnabled,
      regions: dynamicScalingEnabled ? [] : ["na-east", "na-west"],
      regionLabel: dynamicScalingEnabled ? "region" : "dataCenter",
    }),
  }));
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<h1>Home</h1>} />
          <Route path="applications" element={<h1>Applications page</h1>} />
          <Route
            path="capacity"
            element={
              <DynamicScalingRoute>
                <h1>Capacity page</h1>
              </DynamicScalingRoute>
            }
          />
          <Route
            path="capacity/:groupId"
            element={
              <DynamicScalingRoute>
                <h1>Group capacity page</h1>
              </DynamicScalingRoute>
            }
          />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe("static-fleet UI gating", () => {
  beforeEach(() => __resetPlatformCapabilitiesCache());
  afterEach(() => vi.unstubAllGlobals());

  it("shows the Capacity tab on a deployment that provisions its own workers", async () => {
    stubCapabilities(true);
    renderAt("/applications");
    expect(await screen.findByRole("link", { name: "Capacity" })).toBeInTheDocument();
  });

  it("hides the Capacity tab on an operator-managed fleet — every control on it is "
     + "spin / restart / drain, none of which apply", async () => {
    stubCapabilities(false);
    renderAt("/applications");

    // Applications is always present; wait for it, then assert Capacity is not.
    expect(await screen.findByRole("link", { name: "Applications" })).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByRole("link", { name: "Capacity" })).not.toBeInTheDocument());
  });

  it("a bookmarked /capacity redirects to /applications rather than 404ing — the tab is not a "
     + "broken URL, it is a feature this deployment doesn't use", async () => {
    stubCapabilities(false);
    renderAt("/capacity");

    expect(await screen.findByText("Applications page")).toBeInTheDocument();
    expect(screen.queryByText("Capacity page")).not.toBeInTheDocument();
  });

  it("a bookmarked per-group /capacity/{groupId} redirects the same way on a static fleet", async () => {
    stubCapabilities(false);
    renderAt("/capacity/cps");
    expect(await screen.findByText("Applications page")).toBeInTheDocument();
    expect(screen.queryByText("Group capacity page")).not.toBeInTheDocument();
  });

  it("/capacity still renders normally when provisioning is enabled", async () => {
    stubCapabilities(true);
    renderAt("/capacity");
    expect(await screen.findByText("Capacity page")).toBeInTheDocument();
  });

  it("never flashes the Capacity page before the probe resolves", async () => {
    // A fetch that never settles keeps the hook in its loading state.
    vi.stubGlobal("fetch", vi.fn().mockReturnValue(new Promise(() => {})));
    renderAt("/capacity");
    expect(screen.queryByText("Capacity page")).not.toBeInTheDocument();
  });
});
