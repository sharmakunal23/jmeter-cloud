import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { DataCentersSection } from "../DataCentersSection";
import { __resetPlatformCapabilitiesCache } from "../../hooks/usePlatformCapabilities";
import { CapacityApiError, capacityApi } from "../../api/capacity";

// The workers are the application GROUP's pool.
const APP = "cps";

function snapshot(pods: unknown[] = []) {
  return {
    groupId: APP, region: "na-east", maxAvailable: pods.length,
    provisioned: pods.length, ready: pods.length, inUse: 0, spinnable: 0, pods,
  };
}

function worker(overrides: Record<string, unknown> = {}) {
  return {
    podName: "payments-na-east-worker-1",
    state: "READY",
    containerRunning: true,
    lastHeartbeat: "2026-07-27T12:00:00Z",
    ...overrides,
  };
}

describe("DataCentersSection", () => {
  beforeEach(() => {
    __resetPlatformCapabilitiesCache();
    // The section renders in static mode; capabilities only drive vocabulary here.
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({
        provisioningMode: "STATIC", dynamicScalingEnabled: false,
        podRecyclingEnabled: false, regions: ["na-east"], regionLabel: "dataCenter",
      }),
    }));
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("lists declared workers per data center, fetched by group id", async () => {
    const listPods = vi.spyOn(capacityApi, "listPods").mockResolvedValue(snapshot([worker()]) as never);

    render(<DataCentersSection groupId={APP} groupName="Servicing MQ" regions={["na-east"]} />);

    expect(await screen.findByText("payments-na-east-worker-1")).toBeInTheDocument();
    expect(screen.getByText("Ready")).toBeInTheDocument();
    expect(screen.getByText("1 declared")).toBeInTheDocument();
    // The section names the pool it declares into — the group's, shared by its applications.
    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent(/Workers of group\s*Servicing MQ/);
    expect(listPods).toHaveBeenCalledWith(APP, "na-east");
  });

  it("declares a worker with the name and address the operator supplies", async () => {
    vi.spyOn(capacityApi, "listPods").mockResolvedValue(snapshot() as never);
    const declare = vi.spyOn(capacityApi, "declareWorker").mockResolvedValue({
      podName: "w-1", groupId: APP, region: "na-east",
      baseUrl: "http://w-1:8080", source: "STATIC",
      reachable: true, declared: 1, maxAvailable: 1,
    } as never);

    render(<DataCentersSection groupId={APP} groupName="Servicing MQ" regions={["na-east"]} />);
    fireEvent.click(await screen.findByRole("button", { name: /Declare a worker/ }));
    fireEvent.change(screen.getByPlaceholderText(/worker-1$/), { target: { value: "w-1" } });
    fireEvent.change(screen.getByPlaceholderText(/^http/), { target: { value: "http://w-1:8080" } });
    fireEvent.click(screen.getByRole("button", { name: "Declare" }));

    await waitFor(() =>
      expect(declare).toHaveBeenCalledWith(APP, "na-east", "w-1", "http://w-1:8080", false));
  });

  it("an unreachable address offers 'Declare anyway' rather than dead-ending — a worker is "
     + "often declared during a rollout, before it answers", async () => {
    vi.spyOn(capacityApi, "listPods").mockResolvedValue(snapshot() as never);
    const declare = vi.spyOn(capacityApi, "declareWorker")
      .mockRejectedValueOnce(new CapacityApiError(
        400, "WORKER_UNREACHABLE", "worker w-1 did not answer at http://w-1:8080/actuator/health"))
      .mockResolvedValueOnce({
        podName: "w-1", groupId: APP, region: "na-east", baseUrl: "http://w-1:8080",
        source: "STATIC", reachable: false, declared: 1, maxAvailable: 1,
      } as never);

    render(<DataCentersSection groupId={APP} groupName="Servicing MQ" regions={["na-east"]} />);
    fireEvent.click(await screen.findByRole("button", { name: /Declare a worker/ }));
    fireEvent.change(screen.getByPlaceholderText(/worker-1$/), { target: { value: "w-1" } });
    fireEvent.change(screen.getByPlaceholderText(/^http/), { target: { value: "http://w-1:8080" } });
    fireEvent.click(screen.getByRole("button", { name: "Declare" }));

    const anyway = await screen.findByRole("button", { name: /Declare anyway/ });
    fireEvent.click(anyway);

    await waitFor(() =>
      expect(declare).toHaveBeenLastCalledWith(APP, "na-east", "w-1", "http://w-1:8080", true));
  });

  it("a worker running a test cannot be released", async () => {
    vi.spyOn(capacityApi, "listPods").mockResolvedValue(
      snapshot([worker({
        state: "IN_USE",
        blockedBy: { runId: "01RUN", state: "RUNNING", initiatedBy: "kunal" },
      })]) as never,
    );

    render(<DataCentersSection groupId={APP} groupName="Servicing MQ" regions={["na-east"]} />);

    expect(await screen.findByText("Running a test")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Release" })).toBeDisabled();
  });

  it("releasing says the worker keeps running — it was never ours to stop", async () => {
    vi.spyOn(capacityApi, "listPods").mockResolvedValue(snapshot([worker()]) as never);
    const drain = vi.spyOn(capacityApi, "drainPod").mockResolvedValue(
      { podName: "payments-na-east-worker-1", drained: true, containerStopped: false } as never);
    vi.spyOn(window, "confirm").mockReturnValue(true);

    render(<DataCentersSection groupId={APP} groupName="Servicing MQ" regions={["na-east"]} />);
    fireEvent.click(await screen.findByRole("button", { name: "Release" }));

    await waitFor(() => expect(drain).toHaveBeenCalled());
    expect(await screen.findByText(/still running/)).toBeInTheDocument();
  });

  it("a worker the probe can't reach reads as 'Not answering', not the raw registry state", async () => {
    vi.spyOn(capacityApi, "listPods").mockResolvedValue(
      snapshot([worker({ state: "LOST" })]) as never);

    render(<DataCentersSection groupId={APP} groupName="Servicing MQ" regions={["na-east"]} />);

    expect(await screen.findByText("Not answering")).toBeInTheDocument();
  });

  it("with no data centers configured it explains what to do instead of rendering an empty box", async () => {
    render(<DataCentersSection groupId={APP} groupName="Servicing MQ" regions={[]} />);
    expect(await screen.findByText(/No data centers configured/)).toBeInTheDocument();
  });

  it("a worker already declared to another group says which one — release it there first", async () => {
    vi.spyOn(capacityApi, "listPods").mockResolvedValue(snapshot() as never);
    vi.spyOn(capacityApi, "declareWorker").mockRejectedValue(new CapacityApiError(
      409, "POD_BOUND_ELSEWHERE", "bound", undefined, { boundGroupId: "demo" }));

    render(<DataCentersSection groupId={APP} groupName="Servicing MQ" regions={["na-east"]} />);
    fireEvent.click(await screen.findByRole("button", { name: /Declare a worker/ }));
    fireEvent.change(screen.getByPlaceholderText(/worker-1$/), { target: { value: "w-1" } });
    fireEvent.change(screen.getByPlaceholderText(/^http/), { target: { value: "http://w-1:8080" } });
    fireEvent.click(screen.getByRole("button", { name: "Declare" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/already declared to group "demo"/);
  });
});
