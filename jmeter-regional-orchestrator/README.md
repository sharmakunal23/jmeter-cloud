# jmeter-regional-orchestrator

The in-cluster arm of the global orchestrator on **port 8088**, one per
data-center cluster: it creates and deletes worker Pods through its own
ServiceAccount, reports their liveness straight from the Pod list, and relays
the global's calls to workers whose DNS is cluster-private. It holds no state
and never calls the hub — the global is its only client, and workers call the
hub for data (metrics, artifacts) only.

![regional-orchestrator flow](docs/diagrams/regionalOrchestrator.svg)

API contract: [`api/openapi.yaml`](api/openapi.yaml) — browsable at
<http://localhost:8088/swagger-ui.html>.

## Worker Pods on a hosted cluster (PRIVATE-CLOUD-ALIGNMENT Track 8)

The namespace's `ResourceQuota` and `LimitRange` dictate the worker Pod's shape;
read them first (`kubectl get resourcequota,limitrange -n <ns>`) and set the
knobs in `kube/kustomize/overlays/<env>/deployment.yml`.

| Env | Effect on every worker Pod | Default |
|---|---|---|
| `PODPROVISIONER_CPU_MEMORY_RESOURCES` | `false` under a hard-zero quota (`requests.cpu: 0`): no cpu/memory keys at all | `true` |
| `PODPROVISIONER_WORKER_CPU_LIMIT` | a CPU limit — required where the quota counts `limits.cpu` | none (request only) |
| `PODPROVISIONER_WORKER_EPHEMERAL_STORAGE` | request == limit (`maxLimitRequestRatio: 1`); the JTL lives here | the LimitRange default |
| `PODPROVISIONER_SERVICE_ACCOUNT_NAME` | the Pod's SA (token never mounted) | the namespace default |
| `PODPROVISIONER_IMAGE_PULL_SECRET` | pod-level `imagePullSecrets` for a private worker image | none |
| `PODPROVISIONER_RUN_AS_USER` / `RUN_AS_GROUP` / `FS_GROUP` | pod `securityContext` (`runAsNonRoot` follows) | the image's user |
| `PODPROVISIONER_EXTRA_LABELS` | `k=v,k2=v2` on every worker Pod | none |
| `PODPROVISIONER_WORKER_JAVA_OPTS` | the orchestrator JVM's flags (`JAVA_OPTS`) | the image default |

Every worker gets the three-probe pattern — startup and readiness on
`/actuator/health` (usable only once its ingest probe is green), liveness on
`/actuator/keepalive` — and never a cluster credential.

**Capacity guard.** Before creating a Pod the regional reads the namespace
quotas (`resourcequotas` get/list in its Role) and refuses a spin that cannot
fit with `409 CAPACITY_EXHAUSTED` carrying the headroom; the same headroom is
published as `capacity.workersFree` on `GET /api/v1/capabilities`, which the
hub polls, so a run whose shortfall exceeds it fails **before** any Pod is
created (`region … can schedule N more worker(s) …`). A namespace without
quotas is unbounded.

**Egress.** The hosted namespace inherits an invisible default-deny Calico
tier: `overlays/<env>/network-policy-custom.yml` must allow DNS, the cluster
API by its control-plane **endpoint IPs on 6443** (`kubectl get endpoints
kubernetes -n default` — Calico filters after DNAT, so the ClusterIP never
matches), the workers on 8080, and for the workers the hub's data plane FQDNs
plus the systems under test. `connect=0.000000` on an in-pod curl to the API
is that policy, not RBAC.
