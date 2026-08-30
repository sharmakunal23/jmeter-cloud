# Static fleet — running against workers you deploy yourself

**Who this is for:** a private cloud where the control plane cannot create
worker Pods on demand — no API rights in the worker namespace, or a hard
resource quota that means capacity is negotiated up front rather than grown
on demand.

In this mode you deploy workers with `kubectl` and **declare** them to the
platform. Everything downstream is unchanged: runs claim declared workers,
fan out to them, collect 15-second metric windows, and save results.

> **This IS the default.** Since 2026-07-27 `PROVISIONING_MODE` defaults to
> `STATIC`, because operator-managed fleets are this platform's normal
> deployment shape. A deployment that CAN create its own workers — including
> local compose dev, where the Capacity tab's Spin button depends on it —
> must now set `PROVISIONING_MODE=DYNAMIC` explicitly.

---

> Before choosing STATIC because "the namespace can't create Pods": the
> regional's capacity guard (Track 8) reads the namespace quota and refuses
> only what cannot fit — a quota that admits N workers runs DYNAMIC up to N.

## What changes when you flip it

| | `DYNAMIC` | `STATIC` (default) |
|---|---|---|
| Who creates workers | the control plane (`PodProvisioner`) | **you**, with `kubectl` |
| Capacity tab | the worker-management surface | **hidden** — redirects to Applications |
| Worker management | Capacity (per group) → spin / restart / drain | Application detail → **Data centers** → declare / release into the app's group pool |
| `maxAvailable` | you set it; spin enforces it | **derived** — always equals the declared count |
| Liveness | the kubelet, read through the regional's Pod list (`WorkerLivenessProbe`, 15 s) | platform probes the worker (`StaticPodProbe`, 30 s) |
| Releasing a worker | stops + removes the container | removes the registry row; **your worker keeps running** |
| Reconciler / recycler | running | **not wired at all** |
| Placement axis called | "Region" | "Data center" |

**Why the reconciler is off, and why it matters:** `PodReconciler`'s
row-first pass deletes any registry row whose container it cannot see. With
no substrate access it sees nothing, so it would read your entire declared
fleet as orphaned and delete it at every boot. In static mode the bean does
not exist — a structural guarantee, not a flag check.

---

## Configure the control plane

Two env vars on `jmeter-global-orchestrator` (port 8082).
**`STATIC` is already the default** — set these only to be explicit, or to
select `DYNAMIC`:

```yaml
- name: PROVISIONING_MODE
  value: STATIC
- name: REGIONS                   # your data centers, comma-separated (`id`, or `id=url` when the DC runs a jmeter-regional-orchestrator)
  value: "na-east,na-west"
```

Compose: set them in `.env` (both are already plumbed through
`jmeter-global-orchestrator/docker-compose.yml`).
Kubernetes: patch `jmeter-global-orchestrator/kube/kustomize/base/deployment.yml` in
your overlay.

Confirm the control plane agrees with you before going further:

```bash
curl -s localhost:8082/api/v1/platform/capabilities
# {"provisioningMode":"STATIC","dynamicScalingEnabled":false,
#  "podRecyclingEnabled":false,"regions":["na-east","na-west"],
#  "regionLabel":"dataCenter"}
```

The UI reads exactly this to decide what to render, so if the output looks
right the screens will too.

---

## Deploy a worker

Workers run the same `jmeter-local-orchestrator` image the dynamic
substrate uses. What it needs:

| Env var | Required? | Why |
|---|---|---|
| `METRICS_INGEST_URL` | **yes** | where the 15-second metric windows are POSTed. Without it the run produces no data. |
| `BASE_DIR` | yes (image default is fine) | working root; also how the worker recognises its own JMeter processes |
| `POD_ID` | recommended | the worker's id. **Must equal the name you declare** — it is also the `workerId` stamped on every metric, so the metrics join breaks if they differ. Defaults to the hostname, which is normally what you want. |
| `GLOBAL_ORCHESTRATOR_URL` + `GROUP_ID` | optional | enables self-registration (the group whose pool the worker joins). Harmless alongside declaring — the two converge on one row, and your declared address wins. |

```bash
klogin -a na-east
kubectl apply -f my-jmeter-workers.yaml
kubectl get pods -o wide          # note each worker's address
```

**Connectivity is bidirectional and both directions are required:** the
control plane must reach each worker (fan-out, status polls, the liveness
probe) and each worker must reach the metrics-consumer
(`METRICS_INGEST_URL`).

---

## Declare your workers

**UI:** Applications → *your app* → **Data centers** → *Declare a worker*.
The worker joins the pool of the app's group — every application in that
group can claim it. Give it the pod name and the address **the platform can reach it at** —
which is not necessarily the address the worker sees itself as. The address
is probed before the declaration is accepted, so a typo fails immediately
rather than at your next run; *Declare anyway* skips that check for a worker
that is deployed but not up yet.

**API:**

```bash
curl -X PUT \
  "localhost:8082/api/v1/applicationGroups/${GROUP_ID}/capacity/na-east/pods/payments-na-east-worker-1" \
  -H 'Content-Type: application/json' \
  -d '{"baseUrl":"http://payments-na-east-worker-1.workers:8080"}'
# 201 {"podName":"…","source":"STATIC","reachable":true,"declared":1,"maxAvailable":1}
```

Declaring is idempotent — re-declare the same name to correct its address.
Add `?force=true` to accept an address that doesn't answer yet.

**The data center does not need to exist first.** Declaring into a new one
creates its capacity row, because capacity is derived from the declared
count. (Releasing the last worker drives it back to 0 rather than removing
the row; remove the data center itself on the group's Capacity page if you
want it gone from the picker.)

> **A freshly created group already lists your data centers.**
> Creating a group seeds a capacity row at 0 for each region in
> `REGIONS`, so its applications open showing exactly the places you
> can declare workers into. (A deployment that sets no regions still gets
> the historical single `us-east-1` starter row.)

---

## Run a test

Unchanged. Launch from Applications → *your app* → **Start a new run**,
allocating workers per data center. Capacity is derived, so "how many can I
ask for" is simply "how many you declared into the group" — minus what the
group's other applications are running right now.

If you ask for more than are ready you get the shortfall prompt — on a
static fleet it offers *proceed with what's ready* and tells you to deploy
and declare another worker, rather than offering a spin that cannot happen.

---

## Day-2

**A worker stops answering.** The probe stops refreshing it and the sweeper
marks it `LOST` after `globalOrchestrator.pod.lostAfterMs` (90 s); the UI
shows **Not answering**. Fix the worker and it returns to Ready on the next
probe with no action from you. Nothing needs re-declaring.

**Retiring a worker.** *Release* it in the UI first (removes it from the
registry so no new test is sent to it), then `kubectl delete` it. Releasing
is refused while it is running a test — abort the run first.

**Replacing the image.** Roll your workers however you normally do. The
platform does not police worker images in this mode (`IMAGE_MISMATCH`
recycling is a dynamic-substrate feature), so nothing fights your rollout.

**Workers clean up after themselves.** Because a declared worker is never
recycled, each one reaps a JMeter process that outlived its run (before
every run and on a 60 s idle tick) and bounds the artifacts kept back for
diagnosis. The knobs are `ORPHAN_JMETER_POLICY`, `RUN_ARTIFACT_RETENTION_COUNT` and
`RUN_ARTIFACT_RETENTION_DAYS` (validated in the worker's `OrchestratorConfig`; the
effective values show on its `GET /api/v1/config`).
A worker that finds an orphan it cannot kill reports `NOT READY` and takes
itself out of rotation rather than running a test whose numbers would be
quietly wrong.

**A run fails with "worker is already running a test this run did not
start".** Something outside this control plane is using that worker — a
hand-run JMeter, or a second environment pointed at the same fleet. The
message names the run id holding it.

---

## Security note before you expose this

The declare endpoint accepts an operator-supplied URL that the control plane
then fetches every 30 s from every replica. Input validation rejects the
cheap abuses (non-HTTP schemes, embedded credentials, query/fragment
smuggling), but **there is no host allowlist** — consistent with the
platform's internal-use posture. This is why the S-11 target-host
allowlist and egress controls are mandatory, not optional, before this is
reachable from anywhere untrusted.

---

## Scope

Static mode lives in `jmeter-global-orchestrator`, the platform's only control
plane since 2026-08-28. **Don't point two control planes at one declared
fleet** — each keeps its own registry, so both would hand runs to the same
workers and neither would know; the symptom is the foreign-run error in
*Day-2* above, on every other launch.
