# Network access matrix — who talks to whom

Every connection the platform opens, verified from the clients in the code
(2026-08-30). Write the hosted network policies from this table; the Calico
templates in each service's `kube/kustomize/overlays/<env>/network-policy-custom.yml`
encode the same rows. Direction is always *source → destination*; nothing
here is bidirectional.

## The datastore rule

**Only two services open a database connection: `jmeter-global-orchestrator`
and `jmeter-metrics-consumer`.** Everything else is stateless — no JDBC, no
cache, no queue (the document-service keeps blobs on a mounted volume, which
is a file system, not a datastore). The Flyway Job is the third Oracle
principal, at deploy time only.

| Service | Oracle | Redis | Mail | Holds state |
|---|---|---|---|---|
| `jmeter-global-orchestrator` | yes — 3 pools on `CARDZATE_DB_GRAF` (`GLOBAL_ORCHESTRATOR_WRITER` on the `ORCH_*` tables; `METRICS_READER` + `METRICS_PURGER` on the group facts) | yes (terminal-run cache) | yes (SMTP, notifications) | the control plane |
| `jmeter-metrics-consumer` | yes — 1 pool (`CARDZATE_DB_GRAF` owner) | no | no | none (insert-only edge) |
| `oracle/` Flyway Job | yes — the schema owner | no | no | deploy time only |
| `jmeter-regional-orchestrator` | **no** | no | no | none |
| `jmeter-local-orchestrator` (worker) | **no** | no | no | its run's files + a disk buffer of unsent envelopes |
| `document-service` | **no** | no | no | the blob volume (file system) |
| `jmeter-cloud-ui` | **no** | no | no | none |

## Ingress — what may reach each service

| Destination | Port | From | Purpose |
|---|---|---|---|
| `jmeter-cloud-ui` | 80 | the ingress controller (browsers) | the SPA |
| `jmeter-global-orchestrator` | 8082 | the UI's nginx (`/api/*`, `/actuator/*` proxied) | every UI call |
| | 8082 | **declared (STATIC) workers only** | `POST /api/v1/registerPod` (carries `groupId`) + heartbeats — dynamic workers never call the hub |
| `jmeter-metrics-consumer` | 8083 | workers | `POST /api/v1/ingest?groupId=` (bearer under `cloud`/`dev`/`test`/`prod`) |
| | 8083 | the hub | `GET /actuator/health` (platform-health probe only) |
| `document-service` | 8084 | the UI's nginx (`/api/v1/blob*`) | uploads / downloads |
| | 8084 | workers | fetch test plan + data files, upload results |
| | 8084 | the hub | blob metadata, run archive, purge, `GET /actuator/health/readiness` |
| `jmeter-regional-orchestrator` | 8088 | **the hub only** | `/api/v1/capabilities`, `/api/v1/pods*`, `/api/v1/workers*`, the relay |
| worker Pods | 8080 | the regional (relay) — routed regions | `actuator/health`, `api/v1/test`, `api/v1/test/{drain,abort}`, `api/v1/logs` (the relay allow-list) |
| | 8080 | the hub — direct regions only | the same calls, direct (operator-declared workers) |
| Oracle | 1521 | the hub, the consumer, the Flyway Job | |
| Redis | 6379 | the hub only | |
| SMTP | 1025 local / the corporate relay | the hub only | run reports + alerts |
| Kubernetes API | 6443 (control-plane endpoint IPs) | the regional only | create / delete / list worker Pods, read their logs and the namespace quotas |

## Egress — what each service opens

| Source | → Destination | Port | Why |
|---|---|---|---|
| browser | UI | 443 | |
| `jmeter-cloud-ui` (nginx) | hub | 8082 (local) / 443 FQDN (hosted) | `API_UPSTREAM` |
| | document-service | 8084 / 443 FQDN | `BLOB_UPSTREAM` — blob uploads bypass the hub |
| `jmeter-global-orchestrator` | Oracle | 1521 | three pools |
| | Redis | 6379 | cache |
| | SMTP relay | 1025 / 25 / 587 | notifications |
| | each regional | 8088 / 443 FQDN | control plane of every data center |
| | document-service | 8084 / 443 FQDN | metadata, archive, purge, readiness probe |
| | metrics-consumer | 8083 / 443 FQDN | `/actuator/health` probe only |
| | declared workers (direct regions) | 8080 | fan-out, drain, abort, properties, logs |
| | `api.anthropic.com` | 443 | AI run analysis (only when a key is set) |
| | **the applications' health endpoints** (operator-configured URLs) | any | `ApplicationHealthPoller` — every minute |
| `jmeter-metrics-consumer` | Oracle | 1521 | the only egress |
| `jmeter-regional-orchestrator` | Kubernetes API | 6443 (endpoint IPs, post-DNAT) | fabric8 |
| | worker Pods | 8080 | the relay |
| worker Pods | metrics-consumer | 8083 / 443 FQDN | ingest |
| | document-service | 8084 / 443 FQDN | artifacts in, results out |
| | hub | 8082 | **declared (STATIC) workers only**: register + heartbeat |
| | **the systems under test** | any | the load itself (SECURITY S-5/S-11 turns this into an allow-list) |
| `document-service` | nothing | — | (an S3 endpoint only under the `-Pcloud` S3 backend) |
| Flyway Job | Oracle | 1521 | migrations |
| everything | DNS | 53 | |

**Worker-pod-internal only (UX-DYNAMICS T5):** the JMeter child's BeanShell
server (`BEANSHELL_PORT`, plus bsh's HTTP twin on 4447) binds all interfaces
but is never a Service port — the worker's NetworkPolicy must NOT admit it
(only 8080). Its only caller is the worker's own orchestrator on `127.0.0.1`,
relaying `POST /api/v1/test/properties` as `props.put` statements (never
scripts). **Secure by default (2026-08-31):** the worker's default is `0`
(off) — bsh is unauthenticated code-exec, so unmanaged environments never
expose it; the managed paths opt in with 4446 (the K8s provisioner stamp
`PODPROVISIONER_BEANSHELL_PORT`, the local driver's dev workers, an
operator's declared-worker env). `0` disables per deployment.

## What is NOT in the picture (and must stay out)

- No service calls the regional except the hub; the regional never calls the hub.
- Dynamic workers never call the hub's control plane (no `GLOBAL_ORCHESTRATOR_URL` is stamped).
- The UI's browser code never reaches a regional, the consumer or the document-service directly — the hub's `GET /api/v1/platform/health` is the health picture, nginx proxies the two data paths.
- The consumer never calls anything but Oracle; the document-service calls nothing.
- Image pulls are the kubelet's, outside every pod policy.

## Hosted notes

One namespace per service (D10): the "FQDN" rows cross namespaces through the
ingress controller, so each source needs a Calico egress rule by domain and each
destination's ingress policy admits the ingress controller's namespace (the
bases do this). The regional's namespace also holds its worker Pods, so its
policies cover both selectors. Fill the `REPLACE_ME_*` placeholders in the
overlays from this table; the checklist is `privateCloudHardening.md` §3/§10.
