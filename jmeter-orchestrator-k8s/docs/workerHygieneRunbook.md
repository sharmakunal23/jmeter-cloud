# Worker hygiene — operator runbook

The platform auto-manages JMeter worker pod hygiene end-to-end. This
runbook covers the three questions operators ask most:

1. **I rebuilt the JMeter local-orchestrator image — what happens?**
2. **I want fresh workers for every run — how?**
3. **I see disk filling on a worker — what's the eviction policy?**

The runbook below is the operator-facing distillation of the worker-hygiene design.

---

## 1. "I rebuilt the image — what happens?"

**Short answer:** within ~60 seconds, every pod still running the old
image gets drain-and-replaced automatically. You don't need to do
anything beyond the rebuild.

**Mechanism**

- `PodRecycler` runs every `globalOrchestrator.pod.recycleIntervalMs`
  (default 60 s) and asks the Docker daemon for the current digest of
  `jmeter-local-orchestrator:dev`.
- For every IDLE pod whose recorded `imageDigest` doesn't match the
  daemon's, the recycler:
  1. Flips `pod.state` to `DRAINING_FOR_RECYCLE` (so concurrent run-claim
     queries skip the pod).
  2. Calls `POST /api/v1/test/drain` (idempotent — 404s for an idle pod
     are treated as success).
  3. `docker stop` + `docker rm` the old container.
  4. Spins a fresh container via `PodSpinService`; the new pod
     re-registers under the same `(applicationId, region)` with a fresh
     `imageDigest` + `runsServed=0`.
- Active-claim pods are skipped — recycling waits until the run ends.
  Next tick after the run terminates picks the pod up.
- Counter: `globalOrchestrator.pods.recycled.image` (Prometheus).

**Verifying after a rebuild**

```bash
# 1. Rebuild.
docker compose --profile manual build jmeter-local-orchestrator-image

# 2. Compare daemon digest vs registry digest.
docker images jmeter-local-orchestrator:dev --no-trunc --format '{{.ID}}'
curl -s http://localhost:8082/api/v1/applications/<APP_ID>/capacity/<region>/pods \
  | jq '.pods[].imageDigest'

# 3. Wait ≤60s (one scheduled tick), then re-check — every pod's
#    imageDigest should match the daemon's current digest.
```

**Force the sweep instead of waiting** — useful in dev:

```bash
curl -X POST http://localhost:8082/api/v1/admin/recyclePods
```

**This is the explicit fix for the 2026-05-15 "drain has no effect"
incident.** Before image-mismatch recycle, an image rebuild left old
pods on the old binary; operator-issued drains hit 404 on new endpoints
those pods didn't know about. Image-mismatch recycle removes that
footgun.

---

## 2. "I want fresh workers for every run — how?"

You have two knobs, depending on what "fresh" means.

### (a) **Per-app policy: EVERY_RUN**

If an application should *always* recycle its pods after each run
(e.g., a regression-baseline app), set its recycle policy to
`EVERY_RUN`:

- **UI:** Capacity tab → app → "Pod recycle policy" section → Edit →
  "Every run (paranoid)" → Save.
- **API:**
  ```
  PUT /api/v1/applications/{id}
  { "name": "<name>", "recyclePolicy": "EVERY_RUN" }
  ```

Every run claim against a pod increments `runsServed` inside the
claim transaction; the next scheduled tick after the run ends flags
the pod for recycle (any pod with `runsServed >= 1` under EVERY_RUN).

### (b) **Bounded reuse: MAX_RUNS / MAX_AGE / BOTH**

Tighter pod lifetimes without per-run churn:

| Policy | Behaviour |
|---|---|
| `REUSE` (default) | Pods live indefinitely; never auto-recycled. |
| `MAX_RUNS` with `maxRunsPerPod=N` | Recycle after N runs. |
| `MAX_AGE` with `podMaxAgeHours=H` | Recycle after H hours (anchored on `provisionedAt`). |
| `BOTH` | Whichever threshold fires first. |

Edit via the same Capacity-tab editor or PUT. Ranges enforced
server-side: `maxRunsPerPod ∈ [1, 10000]`; `podMaxAgeHours ∈ [1, 720]`
(720 h = 30 days).

### (c) **Per-run override (deferred)**

The original design called for a `freshWorkers: true` flag on
`POST /api/v1/runs` to spin a brand-new pod for a single run regardless
of policy. **Deferred** — not implemented yet. Use option (a) for the
same effect when launching from a paranoid-mode app, or option (b) with
a small threshold.

### Spinning workers from the run page

If a run requests more workers than are currently provisioned, the
launcher's submit returns 503 `INSUFFICIENT_CAPACITY` with the
shortfall body, and the UI surfaces a **"Spin missing workers and
launch"** button. Click that → the backend spins to fill the gap
(subject to the per-region `maxAvailable` ceiling), polls the new pods
until reachable, then retries the claim and launches.

If you want this behaviour via API: include `spinShortfall: true` in
the `POST /api/v1/runs` body.

---

## 3. "I see disk filling on a worker — what's the eviction policy?"

**Short answer:** every COMPLETED / DRAINED run is swept eagerly at the
end of the run. The on-disk footprint per pod is bounded by what
*FAILED* runs preserved.

**Mechanism** (`jmeter-local-orchestrator`)

- Every run writes to its own subdirectory:
  - `BASE_DIR/results/{runId}/results.jtl`
  - `BASE_DIR/results/{runId}/.jtlOffset`
  - `BASE_DIR/results/{runId}/.done`
  - `BASE_DIR/logs/{runId}/jmeter.log`
- After the run reaches its terminal state, `TestRunManager.runLifecycle`
  fires a `finally` block:
  - **COMPLETED / DRAINED** → both subdirs deleted (clean exit).
  - **FAILED / ABORTED** → both subdirs *preserved* for postmortem.
  - **COMPLETED-with-upload-FAILED** → preserved so the operator can
    replay the gzipped JTL via `GET /api/v1/results/file?format=zip`.

**To inspect a worker's disk**

```bash
docker exec <pod-name> ls -la /var/lib/jmeter-orchestrator/results/
docker exec <pod-name> ls -la /var/lib/jmeter-orchestrator/logs/
```

A clean worker (no failed runs preserved) will show empty `results/`
and `logs/` directories. Subdirectories that are still there belong to
runs that ended FAILED / ABORTED.

**Cleaning up FAILED-run artifacts**

There's no automatic GC for FAILED runs — forensic data wins over disk
space. To reclaim manually:

```bash
# After you've copied off whatever you need:
docker exec <pod-name> rm -rf /var/lib/jmeter-orchestrator/results/<runId>
docker exec <pod-name> rm -rf /var/lib/jmeter-orchestrator/logs/<runId>
```

Or recycle the pod entirely — pod recycling tears the
container down so the volume goes with it:

```bash
# Drain + replace via the Capacity tab UI, or:
curl -X DELETE \
  http://localhost:8082/api/v1/applications/<APP_ID>/capacity/<region>/pods/<podName>
curl -X POST \
  http://localhost:8082/api/v1/applications/<APP_ID>/capacity/<region>/pods
```

---

## Reference: per-pod chips in the Capacity tab

The pod table on `/capacity/<appName>` shows:

- **State** — READY / IN_USE / LOST / RECYCLING. The `RECYCLING` chip
  has a "Will recycle now (idle)" tooltip; it means
  `pod.state = DRAINING_FOR_RECYCLE` (mid-replace).
- **Runs** — current `runsServed` count. Approaching `maxRunsPerPod` →
  this pod will recycle soon.
- **Age** — relative time since `provisionedAt` (the container-create
  wall-clock). Distinct from `lastHeartbeat` — restarts don't reset it.
- **Image** — sha7-style abbreviation of the pod's `imageDigest`.
  Mismatch with the current `jmeter-local-orchestrator:dev` digest
  → this pod will be image-recycled within ≤60 s.

The same `runsServed` value also surfaces on the run-detail Worker Fleet
tab so operators can spot a near-threshold pod *while the run is live*.

---

## Counters worth watching (Prometheus)

```
globalOrchestrator_pods_recycled_maxRuns_total    # MAX_RUNS / BOTH threshold fired
globalOrchestrator_pods_recycled_maxAge_total     # MAX_AGE / BOTH threshold fired
globalOrchestrator_pods_recycled_everyRun_total   # EVERY_RUN paranoid mode
globalOrchestrator_pods_recycled_image_total      # Image-digest mismatch
globalOrchestrator_pods_recycled_failed_total     # Recycle attempts that errored
```

`recycled_failed_total > 0` deserves a look in the orchestrator logs —
the per-pod error message is in
`PodRecycler.RecycleSummary.errors`.
