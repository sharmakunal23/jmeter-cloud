# Kubernetes deploy — umbrella + conventions

How the platform deploys to Kubernetes (private cloud, validated on
kind). Built by the **KUBE track** — live status and per-phase
checklists in the KUBE track. Compose remains the
primary local dev environment; everything here is additive.

## Layout

Each service owns its manifests, mirroring how each owns its compose
fragment:

```
<service>/kube/
  base/            # kustomization.yaml + manifests, environment-neutral
  overlays/
    kind/          # local validation cluster
    privateCloud/  # the real hosting target
```

This directory holds only what no single service can own:

```
infra/deploy/k8s/
  namespace/           # the shared jmeter-cloud Namespace (a kustomize base)
  kind/                # umbrella: composes every service's kind overlay
  privateCloud/        # umbrella: composes every service's privateCloud overlay
```

The umbrellas are the `kubectl apply -k` analog of the top-level
`docker-compose.yml` `include:` list:

```sh
kubectl apply -k infra/deploy/k8s/kind          # local kind cluster
kubectl apply -k infra/deploy/k8s/privateCloud  # private cloud
```

Service entries inside the umbrella `kustomization.yaml`s are commented
in as each KUBE phase lands, so the umbrellas always build clean.

## Conventions (locked in KUBE-0 — full rationale in the tracker)

1. **Kustomize, not Helm.** Built into kubectl; two overlays cover our
   environments; no new tooling (repo posture: no top-level build tool).
2. **Service-name parity.** Every K8s `Service` is named exactly like
   its compose service (`postgres`, `redis`, `metrics-consumer`,
   `document-service`, `global-orchestrator`, `mailhog`, `grafana`) so
   every existing URL default works unchanged. This is the migration
   contract — breaking parity means hunting config in every consumer.
3. **One namespace: `jmeter-cloud`** for services and the dynamically
   provisioned worker Pods.
4. **Naming.** Directory `kube/`; manifest filenames camelCase
   (`configMap.yaml`, `flywayJob.yaml`); `kustomization.yaml` is
   tool-hardcoded (exempt). Resource names inside manifests are DNS-1123
   lowercase-with-hyphens (the one exemption from the repo's camelCase rule).
5. **`enableServiceLinks: false` on every pod spec.** Kubelet's legacy
   docker-link env injection (`REDIS_PORT=tcp://…`) collides with our
   `${REDIS_PORT:6379}`-style Spring bindings.
6. **Probes from `/actuator/health` — never drop health.** Where a
   service splits probe groups (metrics-consumer), liveness →
   `/actuator/health/liveness`, readiness → `/actuator/health/readiness`;
   a liveness probe on its aggregate health would restart-loop the idle
   consumer (`ingestProgress` flips DOWN after 5 quiet minutes).
7. **Resources from measured compose limits**, requests = limits for JVM
   pods (predictable heap, no overcommit surprises).
8. **Secrets never in manifests.** Bases reference Secrets by name; the
   privateCloud overlay uses a `secretGenerator` reading a git-ignored
   env file (same posture as the repo-root `.env`).
9. **No static worker manifests.** Workers are bare Pods created by the
   provisioner at runtime (headless `workers` Service for DNS); only
   their image needs a registry story.
10. **Common label** `app.kubernetes.io/part-of: jmeter-cloud` is stamped
    by the umbrella (`labels:` transformer, `includeSelectors: false` so
    selectors stay untouched).

## Images & registry (KUBE-8)

Bases reference plain local tags (`jmeter-metrics-consumer:dev`). Each
overlay owns the rewrite via the kustomize `images:` transformer:

```yaml
# overlays/kind — keep local tags, never pull
images:
  - name: jmeter-metrics-consumer
    newTag: dev

# overlays/privateCloud — private registry
images:
  - name: jmeter-metrics-consumer
    newName: registry.internal.example.com/jmeter-cloud/jmeter-metrics-consumer
    newTag: "1.0.0"
```

kind additionally needs local-only images side-loaded (`kind load
docker-image <image>:dev`) since there is no registry to pull from —
build them with `--provenance=false` (BuildKit attestation manifests
break the containerd import) and let public multi-arch images
(postgres:16, grafana, flyway) pull from their registries instead.

### The full image inventory (build + push)

No CI yet (PHASE2 Track E) — build + push is manual. Every image builds
from its subsystem directory except the worker, whose build context is
the repo root:

| Image | Build (from repo root) | Deployed via |
|-------|------------------------|--------------|
| `jmeter-cloud-flyway` | `docker build --provenance=false -t jmeter-cloud-flyway:dev postgres/` | `postgres/kube` Job |
| `jmeter-metrics-consumer` | `docker build --provenance=false -t jmeter-metrics-consumer:dev jmeter-metrics-consumer/` | `jmeter-metrics-consumer/kube` |
| `document-service` | `docker build --provenance=false -t document-service:dev document-service/` | `document-service/kube` |
| `jmeter-global-orchestrator` | `docker build --provenance=false -t jmeter-global-orchestrator:dev jmeter-global-orchestrator/` | `jmeter-global-orchestrator/kube` |
| `jmeter-cloud-ui` | `docker build --provenance=false -t jmeter-cloud-ui:dev jmeter-cloud-ui/` | `jmeter-cloud-ui/kube` |
| `jmeter-local-orchestrator` (worker) | `docker build --provenance=false -t jmeter-local-orchestrator:dev -f jmeter-local-orchestrator/docker/Dockerfile .` | **runtime-stamped** — see below |
| `jmeter-regional-orchestrator` | `docker build --provenance=false -t jmeter-regional-orchestrator:dev jmeter-regional-orchestrator/` | `jmeter-regional-orchestrator/kube` (in the umbrella as `REGION=local`; one per data-center cluster via `local/bootstrapRegions.sh` or its privateCloud overlay) |
| `jmeter` (base) | `jmeter/buildImage.sh` | not deployed by any manifest — the worker image bakes its own JMeter; this base serves standalone/tooling use |

Push pattern (per image): `docker tag <image>:dev
registry.internal.example.com/jmeter-cloud/<image>:<version>` →
`docker push …` → set the same reference in the privateCloud overlay's
`images:` block.

### The worker image is NOT in any manifest

Worker Pods are created at runtime by the orchestrators; the image comes
from **`PODPROVISIONER_IMAGE`**, not a kustomize transformer. In
privateCloud, patch that env var to a registry reference — and pin it as
`repo:tag@sha256:<digest>`: `currentImageDigest()` returns the
CONFIGURED reference, so IMAGE_MISMATCH recycling fires on a *config*
change (deliberate rollout), and a floating tag that someone re-pushes
would… change nothing until the config changes, while a *re-tag in
config* mid-run triggers PodRecycler drains exactly like a local rebuild
does on compose. Never roll `PODPROVISIONER_IMAGE` while runs are
active.

## Private-cloud hardening (KUBE-11)

Everything that must be decided/executed when the privateCloud overlay
meets a real cluster lives in **`privateCloudHardening.md`**: secrets
sourcing options, the four `localdev` password rotations, the
default-deny NetworkPolicy set (`privateCloud/networkPolicies.yaml`,
shipped commented — kind's CNI doesn't enforce policies), Ingress TLS
(S-15), storageClass + backup posture, the D-6 log-based alerting
obligation (HARD — the platform emits no metrics), and the auth
exposure gate (profile `local` only while the cluster is private).

## Boot expectations

Kubernetes has no `depends_on`: services start concurrently and converge
via readiness gates. Initial crash-loops or NotReady while postgres
starts and the Flyway Job applies migrations are **normal** — the stack
settles once the schema exists. Don't "fix" this with startup ordering
hacks; fix a service only if it fails to converge after the DB is ready.

## Static fleets — workers you deploy yourself

If the control plane can't create worker Pods on demand — no API rights in
the worker namespace, or a quota that caps allocation — set
`PROVISIONING_MODE=STATIC` on the global-orchestrator and declare the
workers you deployed with `kubectl`. The Capacity tab is replaced by a
per-application **Data centers** section, `maxAvailable` becomes derived
from the declared count, liveness is probed rather than heartbeated, and
the reconciler + recycler are not wired (the reconciler would otherwise
delete the declared fleet at every boot).

**Full playbook: [`staticFleet.md`](staticFleet.md)** — configuration, what
env a worker needs, declaring, day-2 operations, and the security note.

**STATIC is the platform default since 2026-07-27**; set `PROVISIONING_MODE=DYNAMIC` explicitly on a
deployment that may create its own workers.

## Scaling posture — who can run >1 replica

Every background path in the platform was audited for N-replica
correctness. The per-service verdict:

| Service | Safe replicas | Why |
|---------|---------------|-----|
| `jmeter-cloud-ui` | **any** | nginx + static assets, no state |
| `metrics-consumer` | **any** | ingest is idempotent (`ON CONFLICT (runId, workerId, label, windowSecond) DO NOTHING`); `PartitionMaintenanceJob` is guarded by a Postgres advisory lock so only one replica does the weekly-partition work per tick |
| `global-orchestrator` | **any** (after the MULTI-INSTANCE fixes) | see the guarantees below |
| `jmeter-regional-orchestrator` | **any** | stateless — every call is a cluster API call or a relayed worker call; nothing is cached or scheduled |
| `document-service` | **1** — hard constraint on `LocalFsBlobStore` | RWO PVC + `Recreate` strategy; two replicas can't mount the same volume, and a second writer would fork the blob tree. Scale out **only** after switching to `S3BlobStore` (`-Pcloud`) |
| `postgres` | **1** (StatefulSet) | single primary; replicas are a v2 topic |
| `grafana` | **1** | ConfigMap-provisioned onto emptyDir |

**What makes the orchestrators safe:**

- **Scheduled work is claimed, not computed.** `CronJobScheduler` claims
  due rows with `FOR UPDATE SKIP LOCKED` and advances `nextFireAt` inside
  the claim transaction — each due schedule fires exactly once no matter
  how many replicas poll. Same idiom (guarded set-based `UPDATE`s) in
  `PodSweeper`, `PodRecycler` (`markDrainingForRecycle` is an
  IDLE→DRAINING guarded update; the loser gets rowcount 0), and pod
  registration/adoption (upsert preserving DRAINING).
- **Terminal run transitions are claimed.**
  `updateRunStateClaimingTerminal` flips a run into COMPLETED/FAILED/
  ABORTED only when it is not already terminal, and only the rowcount-1
  winner emits the terminal audit event, clears `saveResults`, and writes
  the `runTrend` snapshot. Without this, two replicas polling the same
  run both observed the transition and double-emitted.
- **Singleton-per-fact audit events use deterministic ids.**
  `RESULTS_SAVED` is keyed `resultsSaved:{runId}:{workerId}`, so the
  `runEvent` PK dedups across replicas — not just same-id retries.
- **IMAGE_MISMATCH has a grace window.** During a rollout two replicas
  run different images and would drain each other's freshly-spun workers
  in a loop. A pod younger than
  `{global,k8s}Orchestrator.pod.imageMismatchMinAgeMs` (default 10 min)
  is left alone; a genuine image change still recycles on the next sweep
  after the window.

Both orchestrator Deployments also set `strategy.rollingUpdate.maxSurge:
0` so a **single-replica** deploy is a strict stop-then-start — the
mixed-image window doesn't exist at `replicas: 1` (the grace window
covers it at `replicas > 1`).

**Known accepted deviation:** `RateLimitFilter` counts in memory, so N
replicas allow up to N× the configured limit. It's a courtesy throttle in
front of an internal control plane, not a security boundary — move it to
Redis (or an Ingress-level limit) if that changes. Likewise the
metrics-consumer's `ingestProgress` health contributor is per-replica: a
replica that happens to receive no batches reports its own idleness, so
read it per-pod, not as a fleet signal. And `PodNameAllocator` can have
two replicas race for the same name slot — the loser fails loudly on the
`podId` primary key (no corruption; the operator retries).

**`StaticPodProbe` takes no lock, deliberately**. N
replicas probing declared workers is N× the HTTP, but the work is
read-only and the write is an idempotent `lastHeartbeat` refresh, so
concurrent probes can't corrupt anything. Locking would be *worse* here:
if a single lock-holding replica hung, nothing would probe and the whole
declared fleet would be swept `LOST`. Unlocked is the more reliable
choice, matching the accepted `ApplicationHealthPoller` deviation.

## Worker provisioning (KUBE-4 GATE — decided 2026-07-22: Option A)

Worker Pods are created by `jmeter-regional-orchestrator`, one per cluster,
under a ServiceAccount with a namespace-scoped pods-only Role
(`jmeter-regional-orchestrator/kube/base/rbac.yaml`) — the only cluster
credential in the platform. The global-orchestrator holds none: it names its
regions in `REGIONS=id=url,…` and forwards pod creation and worker calls to
each region's regional. The `workers` headless Service lives with the
regional too (REGIONAL-SPLIT, 2026-08-28).
