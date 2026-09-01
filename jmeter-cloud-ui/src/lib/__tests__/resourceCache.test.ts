import { beforeEach, describe, expect, it, vi } from "vitest";

import { cached, invalidate, invalidateAll, peek, DEFAULT_TTL_MS } from "../resourceCache";

beforeEach(() => {
  invalidateAll();
  vi.useRealTimers();
});

describe("resourceCache — single-flight", () => {
  it("three callers racing the same key issue ONE request and all get the value", async () => {
    const loader = vi.fn(async () => ["a"]);

    const results = await Promise.all([
      cached("k", loader),
      cached("k", loader),
      cached("k", loader),
    ]);

    expect(loader).toHaveBeenCalledTimes(1);
    expect(results).toEqual([["a"], ["a"], ["a"]]);
  });

  it("shares the in-flight request even when a caller asks for fresh — a burst of refresh clicks is one request", async () => {
    let release: (v: string[]) => void = () => {};
    const loader = vi.fn(() => new Promise<string[]>((r) => { release = r; }));

    const a = cached("k", loader);
    const b = cached("k", loader, { fresh: true });
    release(["v"]);

    expect(await a).toEqual(["v"]);
    expect(await b).toEqual(["v"]);
    expect(loader).toHaveBeenCalledTimes(1);
  });

  it("releases the in-flight slot when the request settles, so the next miss refetches", async () => {
    const loader = vi.fn(async () => ["a"]);
    await cached("k", loader);
    await cached("k", loader, { fresh: true });
    expect(loader).toHaveBeenCalledTimes(2);
  });
});

describe("resourceCache — TTL", () => {
  it("serves a stored value inside the TTL without touching the loader", async () => {
    const loader = vi.fn(async () => ["a"]);
    await cached("k", loader);
    await cached("k", loader);
    await cached("k", loader);
    expect(loader).toHaveBeenCalledTimes(1);
  });

  it("refetches once the stored value ages past the TTL", async () => {
    const loader = vi.fn(async () => ["a"]);
    const now = vi.spyOn(Date, "now");

    now.mockReturnValue(0);
    await cached("k", loader);
    now.mockReturnValue(DEFAULT_TTL_MS - 1);
    await cached("k", loader);
    expect(loader).toHaveBeenCalledTimes(1);

    now.mockReturnValue(DEFAULT_TTL_MS + 1);
    await cached("k", loader);
    expect(loader).toHaveBeenCalledTimes(2);
    now.mockRestore();
  });

  it("fresh bypasses the TTL — a poller sees a change the tick it lands", async () => {
    let value = "first";
    const loader = vi.fn(async () => value);
    expect(await cached("k", loader)).toBe("first");
    value = "second";
    expect(await cached("k", loader, { fresh: true })).toBe("second");
  });
});

describe("resourceCache — invalidation", () => {
  it("invalidate(prefix) clears the key and every key under it", async () => {
    const loader = vi.fn(async () => "v");
    await cached("groups:list", loader);
    await cached("groups:get:cps", loader);
    await cached("apps:list", loader);
    expect(loader).toHaveBeenCalledTimes(3);

    invalidate("groups");

    expect(peek("groups:list")).toBeUndefined();
    expect(peek("groups:get:cps")).toBeUndefined();
    expect(peek("apps:list")).toBe("v");     // a different namespace is untouched
  });

  it("an invalidate DURING a load wins — the in-flight response never resurrects pre-mutation data", async () => {
    let release: (v: string) => void = () => {};
    const loader = vi.fn(() => new Promise<string>((r) => { release = r; }));

    const p = cached("k", loader);          // read starts
    invalidate("k");                        // ...a mutation lands while it is in flight
    release("stale");                       // ...and only then does the read answer
    expect(await p).toBe("stale");          // the caller still gets its answer

    // But nothing was cached, so the next read goes back to the server.
    expect(peek("k")).toBeUndefined();
    const fresh = vi.fn(async () => "after");
    expect(await cached("k", fresh)).toBe("after");
  });

  it("a load that fails after being invalidated does not evict the load that replaced it", async () => {
    let failFirst: (e: Error) => void = () => {};
    const first = vi.fn(() => new Promise<string>((_r, reject) => { failFirst = reject; }));
    const doomed = cached("k", first);

    invalidate("k");
    let releaseSecond: (v: string) => void = () => {};
    const second = vi.fn(() => new Promise<string>((r) => { releaseSecond = r; }));
    const replacement = cached("k", second);
    // A third caller must join the replacement's flight, not open a third request.
    const joiner = cached("k", second);

    failFirst(new Error("late failure"));
    await expect(doomed).rejects.toThrow("late failure");

    releaseSecond("v");
    expect(await replacement).toBe("v");
    expect(await joiner).toBe("v");
    expect(second).toHaveBeenCalledTimes(1);
    expect(peek("k")).toBe("v");
  });

  it("a mutation's invalidate makes the very next read see the write", async () => {
    let stored = "before";
    const loader = vi.fn(async () => stored);
    expect(await cached("k", loader)).toBe("before");

    stored = "after";
    invalidate("k");

    expect(await cached("k", loader)).toBe("after");
  });
});

describe("resourceCache — failures", () => {
  it("a rejected load is not cached: the next call retries", async () => {
    let fail = true;
    const loader = vi.fn(async () => {
      if (fail) throw new Error("boom");
      return "ok";
    });

    await expect(cached("k", loader)).rejects.toThrow("boom");
    fail = false;
    expect(await cached("k", loader)).toBe("ok");
    expect(loader).toHaveBeenCalledTimes(2);
  });

  it("a failed REVALIDATION keeps the previously stored value — a blip must not blank a working page", async () => {
    let fail = false;
    const loader = vi.fn(async () => {
      if (fail) throw new Error("blip");
      return "good";
    });
    expect(await cached("k", loader)).toBe("good");

    fail = true;
    await expect(cached("k", loader, { fresh: true })).rejects.toThrow("blip");

    expect(peek("k")).toBe("good");
    fail = false;
    // Still inside the TTL, so the retained value is served with no request.
    const before = loader.mock.calls.length;
    expect(await cached("k", loader)).toBe("good");
    expect(loader).toHaveBeenCalledTimes(before);
  });
});

describe("resourceCache — abort", () => {
  it("an aborted caller rejects with AbortError, exactly as an uncached fetch did", async () => {
    const ctl = new AbortController();
    let release: (v: string) => void = () => {};
    const loader = vi.fn(() => new Promise<string>((r) => { release = r; }));

    const p = cached("k", loader, { signal: ctl.signal });
    ctl.abort();

    await expect(p).rejects.toMatchObject({ name: "AbortError" });
    release("v");
  });

  it("one caller aborting does NOT cancel the shared load the others are waiting on", async () => {
    const ctl = new AbortController();
    let release: (v: string) => void = () => {};
    const loader = vi.fn(() => new Promise<string>((r) => { release = r; }));

    const aborted = cached("k", loader, { signal: ctl.signal });
    const survivor = cached("k", loader);
    ctl.abort();
    await expect(aborted).rejects.toMatchObject({ name: "AbortError" });

    release("v");
    expect(await survivor).toBe("v");
    expect(peek("k")).toBe("v");
  });

  it("an already-aborted signal rejects without starting a request", async () => {
    const ctl = new AbortController();
    ctl.abort();
    const loader = vi.fn(async () => "v");

    // A value is already cached, so this is the served-from-cache path.
    await cached("k", loader);
    await expect(cached("k", loader, { signal: ctl.signal }))
      .rejects.toMatchObject({ name: "AbortError" });
    expect(loader).toHaveBeenCalledTimes(1);
  });
});
