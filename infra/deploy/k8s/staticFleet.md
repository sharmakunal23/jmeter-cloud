# Declared workers — running against workers you deploy yourself

**Who this is for:** an operator whose deployment pipeline (jules.yml) already
deploys `jmeter-local-orchestrator` instances into a cluster, or whose quota
posture means capacity is negotiated up front rather than grown on demand.

Since CLUSTER-CAPACITY (2026-08-31) there is **no deployment-wide provisioning
mode** — `PROVISIONING_MODE` and `REGIONS` are retired. Declared
(`SOURCE=STATIC`) and spun (`SOURCE=DYNAMIC`) workers coexist in one group
pool, and both count against the group's reservation on the cluster.
Everything downstream is unchanged: runs claim declared workers, fan out to
them, collect 15-second metric windows, and save results.

---

## The three steps

1. **Register the cluster** (once): Clusters → *+ Add cluster*, or
   `POST /api/v1/regions {"region","label","regionalUrl","maxWorkers"}`.
   Registration validates the regional endpoint (reachable, same region id,
   worker image, RBAC, quota) before anything is written — every cluster
   fronts a `jmeter-regional-orchestrator`, declared workers included.
2. **Attach + reserve** (per group): Capacity → *your group* → *Manage
   clusters* (max `maxClustersPerGroup`, default 2) → *Reserve capacity*.
   The sum of every group's reservations never exceeds the cluster's
   `maxWorkers`, so groups cannot fight for resources later.
3. **Declare each worker** you deployed (below). A NEW declaration consumes
   reservation headroom exactly like a spin; releasing frees it.

## Declared vs spun — what differs per worker

| | Spun (`SOURCE=DYNAMIC`) | Declared (`SOURCE=STATIC`) |
|---|---|---|
| Who creates it | the cluster's regional (`POST …/capacity/{region}/pods`) | **you**, with your own deployment |
| Address | cluster-private (`{pod}.workers:8080`), dialled through the relay | **hub-reachable** (an ingress FQDN) — dialled directly, never relayed |
| Liveness | the kubelet, via the regional's Pod list (`WorkerLivenessProbe`, 15 s) | the hub probes `/actuator/health` (`StaticPodProbe`, 30 s) |
| Restart / recycle | the control plane's (`PodRecycler`, IMAGE_MISMATCH, restart button) | **never** — the platform uses it but doesn't manage it |
| Releasing | stops + removes the Pod | removes the registry row; **your worker keeps running** |

`PodReconciler` and `PodRecycler` are always wired but scoped to
`SOURCE=DYNAMIC` rows — a declared fleet can never be reconciled away or
recycled, whatever else changes.

---

## Deploy a worker

Workers run the same `jmeter-local-orchestrator` image the regionals spin.
What it needs:

| Env var | Required? | Why |
|---|---|---|
| `METRICS_INGEST_URL` | **yes** | where the 15-second metric windows are POSTed. Without it the run produces no data. |
| `BEANSHELL_PORT` | no | default `0` = runtime property pushes OFF (the bsh server is unauthenticated code-exec). Set `4446` to enable `POST /runs/{id}/properties`; keep the port unreachable from outside the worker's pod/host. |
| `BASE_DIR` | yes (image default is fine) | working root; also how the worker recognises its own JMeter processes |
| `POD_ID` | recommended | the worker's id. **Must equal the name you declare** — it is also the `workerId` stamped on every metric, so the metrics join breaks if they differ. Defaults to the hostname, which is normally what you want. |
| `GLOBAL_ORCHESTRATOR_URL` + `GROUP_ID` | optional | enables self-registration (the group whose pool the worker joins). Harmless alongside declaring — the two converge on one row, and your declared address wins. |

```bash
klogin -a na-east
kubectl apply -f my-jmeter-workers.yaml
kubectl get pods -o wide          # note each worker's address / ingress host
```

**Connectivity is bidirectional and both directions are required:** the
control plane must reach each worker at its declared `baseUrl` (fan-out,
status polls, the liveness probe) and each worker must reach the
metrics-consumer (`METRICS_INGEST_URL`).

---

## Declare your workers

**UI:** Capacity → *your group* → the cluster's panel → **+ Declare a worker**.
The worker joins the group's pool — every application in that group can claim
it. Give it the pod name and the address **the platform can reach it at** —
which is not necessarily the address the worker sees itself as. The address
is probed before the declaration is accepted, so a typo fails immediately
rather than at your next run; *Declare anyway* skips that check for a worker
that is deployed but not up yet.

**API:**

```bash
curl -X PUT \
  "localhost:8082/api/v1/applicationGroups/${GROUP_ID}/capacity/na-east/pods/payments-na-east-worker-1" \
  -H 'Content-Type: application/json' \
  -d '{"baseUrl":"https://payments-na-east-worker-1.apps.mt-d2.example.net"}'
# 201 {"podName":"…","source":"STATIC","reachable":true,"declared":1,"maxAvailable":5}
```

Declaring is idempotent — re-declare the same name to correct its address.
Add `?force=true` to accept an address that doesn't answer yet. A declare
into a cluster the group holds no reservation on answers `404
CAPACITY_REGION_NOT_FOUND` (attach + reserve first), and one past the
reservation answers `409 APPLICATION_CAPACITY_EXCEEDED`.

---

## Run a test

Unchanged. Launch from Applications → *your app* → **Start a new run**,
allocating workers per cluster. "How many can I ask for" is the group's
reservation there — minus what the group's other applications are running
right now. If you ask for more than are ready you get the shortfall prompt:
provision the gap (spun workers can fill in beside declared ones) or proceed
with what's ready.

---

## Day-2

**A worker stops answering.** The probe stops refreshing it and the sweeper
marks it `LOST` after `globalOrchestrator.pod.lostAfterMs` (90 s); the UI
shows **Not answering**. Fix the worker and it returns to Ready on the next
probe with no action from you. Nothing needs re-declaring.

**Retiring a worker.** *Release* it in the UI first (removes it from the
registry so no new test is sent to it), then `kubectl delete` it. Releasing
is refused while it is running a test — abort the run first. The admin
escape hatch `DELETE /api/v1/admin/pods/{name}` deliberately refuses a
declared worker (`409 POD_SOURCE_STATIC`) — the platform never destroys what
it did not create.

**Replacing the image.** Roll your workers however you normally do. The
platform does not police declared workers' images (`IMAGE_MISMATCH`
recycling only ever targets spun ones), so nothing fights your rollout.

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

Declared workers live in `jmeter-global-orchestrator`, the platform's only
control plane since 2026-08-28. **Don't point two control planes at one
declared fleet** — each keeps its own registry, so both would hand runs to
the same workers and neither would know; the symptom is the foreign-run
error in *Day-2* above, on every other launch.
