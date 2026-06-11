import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";

import { runsApi } from "../runs";
import { getActor, setActor } from "../../actor";

/** Capture the headers fetch was called with on the most recent call. */
function headersOf(call: unknown): Record<string, string> {
  const init = (call as [string, RequestInit])[1];
  return (init?.headers as Record<string, string>) ?? {};
}

function okResponse(): Partial<Response> {
  return { ok: true, status: 200, text: () => Promise.resolve("{}"), headers: new Headers() };
}

describe("X-Actor auto-send + actor cache", () => {
  beforeEach(() => {
    setActor(null);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(okResponse()));
  });
  afterEach(() => {
    setActor(null);
    vi.unstubAllGlobals();
  });

  it("round-trips through localStorage", () => {
    expect(getActor()).toBeNull();
    setActor("  alice  ");
    expect(getActor()).toBe("alice"); // trimmed
    setActor("");
    expect(getActor()).toBeNull(); // blank clears
  });

  it("attaches X-Actor on a POST when an actor is set", async () => {
    setActor("alice");
    await runsApi.scaleDown("01RUNAAAAAAAAAAAAAAAAAAAAA", { workerIds: ["w-1"] });
    const headers = headersOf((fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1));
    expect(headers["X-Actor"]).toBe("alice");
  });

  it("omits X-Actor when no actor is set", async () => {
    await runsApi.scaleDown("01RUNAAAAAAAAAAAAAAAAAAAAA", { workerIds: ["w-1"] });
    const headers = headersOf((fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1));
    expect(headers["X-Actor"]).toBeUndefined();
  });

  it("does not attach X-Actor on a GET", async () => {
    setActor("alice");
    await runsApi.events("01RUNAAAAAAAAAAAAAAAAAAAAA");
    const headers = headersOf((fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1));
    expect(headers["X-Actor"]).toBeUndefined();
  });
});
