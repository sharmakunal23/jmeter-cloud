/**
 * One module-level cache for the reference data every page needs and almost
 * nobody changes — the application registry, groups, clusters, templates,
 * plugins, capabilities.
 *
 * Two properties, and they solve different problems. **Single-flight**: three
 * components mounting together issue one request, not three. **Stale-while-
 * revalidate**: a cached value inside its TTL is returned without a request at
 * all, so walking Apps → Documents → Templates stops refetching the same list
 * on every step.
 *
 * <p><b>Cache what a page displays, never what an action reads.</b> A stale
 * list behind a screen is a cosmetic lag; a stale list behind the run launcher,
 * the scale-up dialog or the workflow builder is a launch built against
 * something that has changed. Those call sites pass `{fresh: true}` — they keep
 * the single-flight dedupe and skip the TTL. The same rule removed the hub's
 * capacity-grid cache, which had ended up under the run-launch gate.
 *
 * <p>Invalidation is explicit and it is the caller's job: a mutation that
 * changes a resource must call {@link invalidate} (or {@link invalidateAll})
 * or the UI shows its own stale write for up to one TTL. The TTL is the
 * backstop for the write path someone forgot, not the mechanism.
 *
 * <p>Deliberately not a component-state cache: it is keyed by URL-ish string,
 * lives for the tab's lifetime, and holds no React state, so reading it never
 * re-renders anything.
 */

/** How long a cached value is served without revalidating. */
export const DEFAULT_TTL_MS = 30_000;

/**
 * The cache namespaces, all four in one place. They live here rather than in
 * the `api/` module that reads each one because a write to one resource often
 * changes another — creating an application moves its group's
 * `applicationCount`, a capacity or cluster write moves a group's grid — and
 * the api modules already import each other, so owning the constant locally
 * would force an import cycle to say so.
 */
export const APPLICATIONS_CACHE = "applications";
export const APPLICATION_GROUPS_CACHE = "applicationGroups";
export const TEMPLATES_CACHE = "templates";
export const PLUGINS_CACHE = "plugins";

interface Entry<T> {
  value?: T;
  /** When `value` was stored. */
  storedAt: number;
  /** The request currently in flight for this key, if any — the single-flight slot. */
  inFlight?: Promise<T>;
}

const entries = new Map<string, Entry<unknown>>();

export interface CachedOptions {
  /** Serve a stored value younger than this without a request. Default 30 s. */
  ttlMs?: number;
  /** Ignore any stored value and refetch, while still sharing one in-flight request. */
  fresh?: boolean;
  /**
   * The caller's abort signal. Aborting rejects *this* caller with an
   * `AbortError` — exactly what an un-cached `fetch` did — but does **not**
   * cancel the shared load, because other components may be waiting on it.
   */
  signal?: AbortSignal;
}

/**
 * The cached value for `key`, fetching through `loader` on a miss.
 *
 * <p>An in-flight request is shared even when `fresh` is set, so a burst of
 * "refresh now" clicks is still one request. A rejected load is never cached —
 * the next call retries — and it leaves any previously stored value in place,
 * so a transient failure does not blank a page that was working.
 */
export function cached<T>(
  key: string,
  loader: (signal?: AbortSignal) => Promise<T>,
  opts: CachedOptions = {},
): Promise<T> {
  const { ttlMs = DEFAULT_TTL_MS, fresh = false, signal } = opts;
  const entry = entries.get(key) as Entry<T> | undefined;

  if (entry?.inFlight) return abortable(entry.inFlight, signal);
  if (!fresh && entry && entry.value !== undefined && Date.now() - entry.storedAt < ttlMs) {
    return abortable(Promise.resolve(entry.value), signal);
  }

  // No AbortSignal is passed down: the request is shared, so one component
  // unmounting must not cancel the load the other two are waiting on.
  //
  // Both handlers write only while this load still owns the slot. A mutation's
  // `invalidate` (or a later load that replaced it) is newer than this response:
  // storing it anyway would resurrect pre-mutation data for a whole TTL, and
  // deleting on failure would tear out the newer load's single-flight slot.
  let inFlight: Promise<T>;
  const stillOurs = () => (entries.get(key) as Entry<T> | undefined)?.inFlight === inFlight;
  inFlight = loader().then(
    (value) => {
      if (stillOurs()) entries.set(key, { value, storedAt: Date.now() });
      return value;
    },
    (err) => {
      // Keep whatever was stored — a failed revalidation is not evidence the
      // old value is wrong, and its original `storedAt` stays so a retained
      // value still ages out — but drop the in-flight slot so the next call retries.
      if (stillOurs()) {
        const current = entries.get(key) as Entry<T>;
        if (current.value !== undefined) {
          entries.set(key, { value: current.value, storedAt: current.storedAt });
        } else {
          entries.delete(key);
        }
      }
      throw err;
    },
  );

  entries.set(key, { ...(entry ?? { storedAt: 0 }), inFlight });
  return abortable(inFlight, signal);
}

/**
 * Rejects with an `AbortError` when `signal` aborts, so a caller that unmounts
 * sees what an un-cached `fetch` gave it. The shared promise underneath keeps
 * running for whoever else is waiting.
 */
function abortable<T>(promise: Promise<T>, signal?: AbortSignal): Promise<T> {
  if (!signal) return promise;
  if (signal.aborted) {
    // Swallow the shared promise's own settlement so an unhandled rejection
    // can never be attributed to the caller that walked away.
    promise.catch(() => {});
    return Promise.reject(abortError());
  }
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => reject(abortError());
    signal.addEventListener("abort", onAbort, { once: true });
    promise.then(resolve, reject).finally(() => signal.removeEventListener("abort", onAbort));
  });
}

function abortError(): Error {
  return typeof DOMException === "function"
    ? new DOMException("The operation was aborted.", "AbortError")
    : Object.assign(new Error("The operation was aborted."), { name: "AbortError" });
}

/**
 * Drops every key that starts with `prefix` (or exactly `prefix`), so the next
 * read refetches. Call it from the mutation, not from the page that displays
 * the result.
 */
export function invalidate(prefix: string): void {
  for (const key of Array.from(entries.keys())) {
    if (key === prefix || key.startsWith(prefix)) entries.delete(key);
  }
}

/** Drops everything — the "something big changed" hammer, and the test reset. */
export function invalidateAll(): void {
  entries.clear();
}

/** Test-only: what is stored for `key` right now, without fetching. */
export function peek<T>(key: string): T | undefined {
  return (entries.get(key) as Entry<T> | undefined)?.value;
}
