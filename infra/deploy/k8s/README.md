# Kubernetes deploy — umbrella + conventions

How the platform deploys to Kubernetes (private cloud, validated on
kind). Built by the **KUBE track** — live status and per-phase
checklists in the KUBE track. Compose remains the
primary local dev environment; everything here is additive.

## Layout (PRIVATE-CLOUD-ALIGNMENT Track 7 — the hosted blueprint)

Each service owns its manifests, in the hosted platform's layout:

```
<service>/
  Dockerfile               # local + kind: multi-stage, public base images, JAVA_OPTS honoured
  Dockerfile.privateCloud  # hosted: managed base image, COPY target/<svc>-${gavVersion}.jar, CMD java $JAVA_OPTS
  Jenkinsfile              # shim → jules.yml
  jules.yml                # build + image + branch → env → cluster deploy map (placeholders)
  kube/kustomize/
    base/                  # deployment.yml service.yml network-policy.yml resource-quota.yml (documented, not applied)
    overlays/
      local/               # token-free: today's kind shape (namespace jmeter-cloud, :dev images)
      dev/ test/ prod/     # ${namespace} ${containerImageUri} ${cluster-host} ${environment} ${cluster} — resolved by the pipeline
```

Two deployment shapes, and only the first has an umbrella:

| Shape | Namespaces | Inter-service URLs | Apply |
|---|---|---|---|
| **local (kind)** | one, `jmeter-cloud` | Service names (`metrics-consumer:8083`) | `kubectl apply -k infra/deploy/k8s/kind` — composes every service's `local` overlay + the local Oracle |
| **hosted (D10)** | one per service and tier: `<sealId>d<appId>-<service>-<env>` | the other services' ingress FQDNs (`https://metrics-consumer.<platform-domain>`), set in each overlay's env | each service's `jules.yml` pipeline (`kustomize build overlays/<env> \| kubectl apply`), never an umbrella |

The old `overlays/{kind,privateCloud}` and the `privateCloud` umbrella are gone
(2026-08-30). Filenames under `kube/kustomize/` mirror the blueprint verbatim
(`.yml`, `network-policy.yml`) — the repo's camelCase rule exempts that tree.

## Conventions (locked in KUBE-0 — full rationale in the tracker)

1. **Kustomize, not Helm.** Built into kubectl; two overlays cover our
   environments; no new tooling (repo posture: no top-level build tool).
2. **Service-name parity.** Every K8s `Service` is named exactly like
   its compose service (`oracle`, `redis`, `metrics-consumer`,
   `document-service`, `global-orchestrator`, `mailhog`) so
   every existing URL default works unchanged. This is the migration
   contract — breaking parity means hunting config in every consumer.
3. **Namespaces.** Local: one, `jmeter-cloud`, for services and the worker
   Pods. Hosted: one per service and tier (D10); the regional's namespace
   also holds the worker Pods it creates, so its quota is sized for them.
4. **Naming.** `kube/kustomize/` mirrors the hosted blueprint (`.yml`,
   `network-policy.yml`, `resource-quota.yml`); resource names are DNS-1123
   lowercase-with-hyphens. `oracle/kube` keeps the older camelCase tree.
5. **`enableServiceLinks: false` on every pod spec.** Kubelet's legacy
   docker-link env injection (`REDIS_PORT=tcp://…`) collides with our
   `${REDIS_PORT:6379}`-style Spring bindings.
6. **Three probes, never on aggregate health.** Startup + liveness →
   `/actuator/keepalive` (process only — a database or storage blip must
   not restart every replica); readiness → `/actuator/health/readiness`
   (the service's dependencies: `db` on the hub and consumer, `storage`
   on document-service). The UI probes `/healthz`. Probes send
   `x-forwarded-proto: https` because the apps run with
   `SERVER_FORWARD_HEADERS_STRATEGY=NATIVE` behind the TLS-terminating
   ingress.
7. **Resources: memory requests = limits** (Guaranteed QoS, the JVM heap is
   fixed by `JAVA_OPTS` on the Deployment — heap ≈ half the limit); every
   container also declares a CPU limit because the platform's namespace
   quota counts `limits.cpu`.
8. **Secrets never in manifests.** Bases reference Secrets by name; the
   `local` overlay generates the compose dev credentials, the hosted tiers
   create them out of band (each overlay's `kustomization.yml` lists the
   keys).
9. **No static worker manifests.** Workers are bare Pods created by the
   provisioner at runtime (headless `workers` Service for DNS); only
   their image needs a registry story.
10. **Common label** `app.kubernetes.io/part-of: jmeter-cloud` is stamped
    by the umbrella (`labels:` transformer, `includeSelectors: false` so
    selectors stay untouched).

## Images & registry

Bases reference `${containerImageUri}` — the digest the pipeline's image
build injects. The `local` overlay patches the plain local tag
(`jmeter-metrics-consumer:dev`) instead; kind side-loads it (`kind load
docker-image <image>:dev`, built with `--provenance=false` because BuildKit
attestation manifests break the containerd import).

### The full image inventory (build + push)

No CI yet (PHASE2 Track E) — build + push is manual. Every image builds
from its subsystem directory except the worker, whose build context is
the repo root:

| Image | Build (from repo root) | Deployed via |
|-------|------------------------|--------------|
| `jmeter-cloud-flyway` | `docker build --provenance=false -t jmeter-cloud-flyway:dev oracle/` | `oracle/kube` Job |
| `jmeter-metrics-consumer` | `docker build --provenance=false -t jmeter-metrics-consumer:dev jmeter-metrics-consumer/` | `jmeter-metrics-consumer/kube` |
| `document-service` | `docker build --provenance=false -t document-service:dev document-service/` | `document-service/kube` |
| `jmeter-global-orchestrator` | `docker build --provenance=false -t jmeter-global-orchestrator:dev jmeter-global-orchestrator/` | `jmeter-global-orchestrator/kube` |
| `jmeter-cloud-ui` | `docker build --provenance=false -t jmeter-cloud-ui:dev jmeter-cloud-ui/` | `jmeter-cloud-ui/kube` |
| `jmeter-local-orchestrator` (worker) | `docker build --provenance=false -t jmeter-local-orchestrator:dev -f jmeter-local-orchestrator/docker/Dockerfile .` | **runtime-stamped** — see below |
| `jmeter-regional-orchestrator` | `docker build --provenance=false -t jmeter-regional-orchestrator:dev jmeter-regional-orchestrator/` | `jmeter-regional-orchestrator/kube` (in the umbrella as `REGION=local`; one per data-center cluster via `local/bootstrapRegions.sh` or its privateCloud overlay) |
| `jmeter` (base) | `jmeter/buildImage.sh` | not deployed by any manifest — the worker image bakes its own JMeter; this base serves standalone/tooling use |

On the hosted platform each service's `jules.yml` builds
`Dockerfile.privateCloud` and injects the digest as `${containerImageUri}`;
nothing is pushed by hand.

### The worker image is NOT in any manifest

Worker Pods are created at runtime by the orchestrators; the image comes
from **`PODPROVISIONER_IMAGE`**, not a kustomize transformer. The hosted
overlays set it to the worker image the worker's own `jules.yml` built — pin it as
`repo:tag@sha256:<digest>`: `currentImageDigest()` returns the
CONFIGURED reference, so IMAGE_MISMATCH recycling fires on a *config*
change (deliberate rollout), and a floating tag that someone re-pushes
would… change nothing until the config changes, while a *re-tag in
config* mid-run triggers PodRecycler drains exactly like a local rebuild
does on compose. Never roll `PODPROVISIONER_IMAGE` while runs are
active.

## Who talks to whom

`networkAccessMatrix.md` is the verified source → destination table (ports,
purpose, direction) the hosted network policies are written from, and states
the datastore rule: **only the global orchestrator and the metrics-consumer
open a database connection; everything else is stateless.**

## Private-cloud hardening (KUBE-11)

Everything that must be decided/executed when the `dev`/`test`/`prod`
overlays meet the hosted clusters lives in **`privateCloudHardening.md`**:
secrets sourcing, the `localdev` password rotations, the per-service
NetworkPolicies (ingress from the ingress controller in every base,
tier egress + the Calico FQDN policies in `network-policy-custom.yml`),
TLS (Contour `HTTPProxy` with the platform wildcard cert), the RWX NFS
storage tiers, the D-6 log-based alerting obligation (HARD — the platform
emits no metrics), and the auth exposure gate (`local` is the only
profile without auth).

## Boot expectations

Kubernetes has no `depends_on`: services start concurrently and converge
via readiness gates. Initial crash-loops or NotReady while oracle
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
| `metrics-consumer` | **any** | ingest is idempotent (staged prune on `(runId, workerId, label, windowSecond)`); `RetentionJob` takes `"maintenanceLock"` `FOR UPDATE SKIP LOCKED`, so replicas never race |
| `global-orchestrator` | **any** (after the MULTI-INSTANCE fixes) | see the guarantees below |
| `jmeter-regional-orchestrator` | **any** | stateless — every call is a cluster API call or a relayed worker call; nothing is cached or scheduled |
| `document-service` | **1** — hard constraint on `LocalFsBlobStore` | RWO PVC + `Recreate` strategy; two replicas can't mount the same volume, and a second writer would fork the blob tree. Scale out **only** after switching to `S3BlobStore` (`-Pcloud`) |
| `oracle` | **1** (StatefulSet on kind; the operator's instance on privateCloud) | single instance; HA is the database's own concern |

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
(`jmeter-regional-orchestrator/kube/kustomize/base/rbac.yml`) — the only cluster
credential in the platform. The global-orchestrator holds none: it names its
regions in `REGIONS=id=url,…` and forwards pod creation and worker calls to
each region's regional. The `workers` headless Service lives with the
regional too (REGIONAL-SPLIT, 2026-08-28).
