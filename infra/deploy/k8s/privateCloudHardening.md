# privateCloudHardening — the KUBE-11 checklist

What must be decided and executed when the services' `kube/kustomize/overlays/{dev,test,prod}`
(deployed one namespace per service by each `jules.yml` pipeline —
PRIVATE-CLOUD-ALIGNMENT Track 7) move from "builds clean" to "hosts the
platform on the real clusters." Each section states the decision, the default recommendation,
and the concrete steps. Execute top to bottom at migration; nothing here
blocks local kind work.


---

## 1. Secrets sourcing (operator decision — pick one before first apply)

Three Secrets exist, all consumed by name (`oracle-credentials`,
`metrics-consumer-credentials`, `global-orchestrator-credentials`,
exact keys documented in each service's
`kube/kustomize/overlays/<env>/kustomization.yml` header).

| Option | When it's the right call |
|--------|--------------------------|
| **Plain Secrets, created out-of-band + RBAC** (the documented `kubectl create secret` commands) | Default floor. Smallest moving-parts count; fine for a single-team internal cluster. Pair with RBAC that denies `get secret` to non-admins and enable EncryptionConfiguration (secrets encrypted at rest in etcd). |
| **External Secrets Operator** | The org already runs Vault / a cloud secrets manager. Secrets stay in the manager; rotation propagates without kubectl access. Recommended where available. |
| **sealed-secrets** | The manifests are GitOps-managed and the team wants secrets in git. Adds a controller + key-backup obligation. |

Whatever the choice: **the three Secret names and keys are the contract**
— the Deployments reference them by name, so any backend that
materializes those Secrets works without manifest changes.

Also rotate the **ANTHROPIC_API_KEY** handling: locally it sits in the
repo-root `.env` (rotation is a standing user-owned action item); in the
private cloud it must ONLY exist as the `anthropicApiKey` key of
`global-orchestrator-credentials`.

## 2. Rotate the four initdb `localdev` passwords (execute during cutover)

`oracle/initdb/01_createSchemasAndUsers.sql` creates the owner `CARDZATE_DB_GRAF` and the
users `METRICS_READER`, `METRICS_PURGER`, `GLOBAL_ORCHESTRATOR_WRITER` with
password `localdev` (public in this repo). On the private cluster,
immediately after first boot:

```sql
ALTER USER CARDZATE_DB_GRAF            IDENTIFIED BY "<new>";
ALTER USER METRICS_READER              IDENTIFIED BY "<new>";
ALTER USER METRICS_PURGER              IDENTIFIED BY "<new>";
ALTER USER GLOBAL_ORCHESTRATOR_WRITER  IDENTIFIED BY "<new>";
```

Then update the consuming Secrets and restart in this order (each pod
only reads credentials at boot):

| Role | Secret (key) | Restart |
|------|--------------|---------|
| `CARDZATE_DB_GRAF` (the schema owner) | `oracle-credentials` (metricsOwnerPassword) | the Flyway Job connects as the owner |
| `CARDZATE_DB_GRAF` (the consumer connects as the owner) | `metrics-consumer-credentials` | `deployment/metrics-consumer` |
| `METRICS_READER`, `METRICS_PURGER` | `global-orchestrator-credentials` (metricsReader*, metricsPurger*) | `deployment/global-orchestrator` |
| `GLOBAL_ORCHESTRATOR_WRITER` | `global-orchestrator-credentials` (globalrunWriter*) | `deployment/global-orchestrator` |

## 3. NetworkPolicies (manifest ready — enable when the CNI enforces)

The rows to encode are `networkAccessMatrix.md` — every connection the code
opens, by source, destination, port and purpose. The Calico templates in each
`overlays/<env>/network-policy-custom.yml` already follow it; fill the
`REPLACE_ME_*` placeholders (API-server endpoint IPs, the Oracle host, the SMTP
relay, the SUT domains, the applications' health-endpoint hosts).

The full default-deny set — encoded per service in
`kube/kustomize/overlays/<env>/network-policy-custom.yml` — is one allow per seam:

- ingress-controller → **ui**:80; ui → **global**:8082 + **document**:8084
- global → each **regional**:8088 (the `REGIONS` URLs — external when the
  data center is another cluster), **oracle**:1521, **redis**:6379,
  api.anthropic.com (443), corporate SMTP. The global never talks to a
  worker or a kube-apiserver directly.
- regional → kube-apiserver (its own cluster), **workers**:8080
- workers → consumer:8083, document:8084 — via **open egress** (the SUT
  address is per-run operator input; tightening = S-5/S-11, deferred).
  Only operator-declared workers also call global:8082 (register/heartbeat);
  workers a regional creates never do.
- consumer → oracle; flyway Job → oracle
- everyone → kube-dns:53

kind's kindnet doesn't enforce NetworkPolicy, so nothing local tests these;
on the enforcing cluster fill the placeholders (ingress-controller
namespace, SMTP egress) and verify kubelet probes still pass under
default-deny on your CNI.

## 4. TLS at the Ingress (SECURITY S-15 lands here)

Every hosted overlay carries a Contour `HTTPProxy` (`overlays/<env>/ingress.yml`)
terminating TLS with the platform's wildcard cert
(`ingress-contour/ingress-contour-default-ssl-cert`); the pod speaks plain
HTTP behind `SERVER_FORWARD_HEADERS_STRATEGY=NATIVE`. On a cluster without
Contour:

1. Set the real hostname + `ingressClassName`.
2. Certificate: **cert-manager** with the org's internal CA issuer
   (recommended — auto-renewal), or a manually-provisioned cert Secret
   (`kubectl create secret tls jmeter-cloud-tls ...`).
3. Force HTTPS (`nginx.ingress.kubernetes.io/ssl-redirect: "true"`) and
   add HSTS once the cert is stable.

Keep the body-size/read-timeout annotations as shipped (1024m/600s) —
they mirror the pod nginx's blob-upload limits.

## 5. storageClass + backup posture

| PVC | Contents | Posture |
|-----|----------|---------|
| `oracle` (kind StatefulSet template, 10Gi; the operator's instance on privateCloud) | ALL platform state: runs, registry, capacity, audit trail, metrics partitions, AI cache | Set an explicit `storageClassName` (SSD-class, `allowVolumeExpansion: true`) on kind; on privateCloud the instance is the DBA's. **Backups are mandatory**: RMAN or Data Pump on a schedule (or CSI VolumeSnapshots for the kind volume). Test a restore before go-live. Retention is each group's nightly `<P>_NIGHTLY_MAINT` job inside the database (archive after `hotDays`, prune after `historyDays`, stats), rendered from `oracle/groups/<id>.json` — no consumer setting, no partition runway to maintain, no external cron. |
| `document-service-data` (10Gi) | Test plans, data zips, saved JTL archives | Same storageClass treatment. Backup optional-but-recommended (artifacts are re-uploadable; saved results are not). Growth = operator-driven; alert on PVC usage >80%. |

Set an explicit `storageClassName` on the kind StatefulSet's volume template
(`oracle/kube/local`) rather than relying on the cluster default.

## 6. Alerting obligation (SLIMDOWN D-6, generalized — HARD requirement)

The platform emits **no metrics**: the private
cloud's log/monitoring stack must recreate the four retired abuse/health
thresholds from the services' JSON logs, plus scrape-free health:

| Signal | Source (JSON logs, one object/line) |
|--------|-------------------------------------|
| Rate-limit rejections | global-orchestrator throttled `RATE_LIMITED` WARN |
| Client-error rate | every service's `AccessLogFilter` line (`status` field, 4xx ratio per service) + metrics-consumer `INGEST_BAD_JSON` / `INGEST_TOO_LARGE` WARNs |
| Concurrent runs | queryable from the control-plane tables (`SELECT COUNT(*) FROM CARDZATE_DB_GRAF.ORCH_RUN WHERE STATE = 'RUNNING'`) — schedule it |
| Upload byte rate | document-service INFO line with `sizeBytes` |
| Health / restarts | kubelet is the prober — alert on `kube_pod_container_status_restarts_total`-equivalent from the cluster's own monitoring, pod NotReady >5 min, and Flyway Job failures. Do NOT re-add app metrics endpoints for this. |

Ship the log pipeline (Fluent Bit/Vector → the org's log store) as part
of the migration, not after — a load platform without abuse alerts is an
incident generator. Same posture as the CloudWatch obligation recorded
in SECURITY S-0/D-6 for the AWS path.

## 7. Auth posture (unchanged rule, restated as the exposure gate)

Spring profile stays **`local` (no auth) ONLY while the cluster is
private/internal** (network-isolated, org-only Ingress). Any wider
exposure — other teams, other networks, the internet — requires the
`cloud` profile auth work first.
NetworkPolicies (§3) and Ingress TLS (§4) are *transport* hardening,
not authentication — they don't move this gate.

## 8. Registry + image pinning (cross-ref KUBE-8)

Per `README.md` "Images & registry (KUBE-8)": every image goes through
the private registry via the `${containerImageUri}` token each `jules.yml` substitutes, and
`PODPROVISIONER_IMAGE` (runtime-stamped, in NO manifest) must be pinned
`repo:tag@sha256:<digest>` — a config re-tag mid-run drains workers
(IMAGE_MISMATCH). Never roll it while runs are active.

## 9. Deferred security work that lands WITH or AFTER this migration

- **S-5/S-6/S-11** (JMX safety scan, SSRF/target-host allowlist) —
  deferred while internal-only; the workers' open-egress policy in §3 is
  the visible reminder.

## 10. Provisioning workers in the hosted cluster (Track 8 — the regional's namespace)

The regional creates worker Pods in its own namespace; everything the platform
injects there shapes them. Work through this before the first `POST /runs`:

```
[ ] Namespace name follows <sealId>d<appId>-jmeter-regional-orchestrator-<env> (jules preDeploy creates it)
[ ] `kubectl get resourcequota,limitrange -n <ns>` read; PODPROVISIONER_* shape set accordingly
    (hard-zero quota → CPU_MEMORY_RESOURCES=false; limits.cpu counted → WORKER_CPU_LIMIT;
     LimitRange ratio 1 → WORKER_EPHEMERAL_STORAGE == its max)
[ ] The quota admits the fleet: pods ≥ workers + 1, memory ≥ workers × PODPROVISIONER_WORKER_MEMORY_MB
    (jules.yml's quota patch == kube/kustomize/base/resource-quota.yml; the capacity guard refuses the rest)
[ ] ServiceAccount + namespaced Role (pods, pods/log, resourcequotas) + RoleBinding applied; Deployment sets serviceAccountName
[ ] Calico egress applied BEFORE the workload: DNS, the cluster API by control-plane ENDPOINT IPs on 6443
    (`kubectl get endpoints kubernetes -n default`, per cluster), workers on 8080; workers → consumer/document-service FQDNs + the SUT domains
[ ] In-pod check: curl -sS -m 15 -o /dev/null -w 'HTTP=%{http_code} connect=%{time_connect}' --cacert …/ca.crt
    -H "Authorization: Bearer $(cat …/token)" https://kubernetes.default.svc/api/v1/namespaces/<ns>/pods → HTTP=200
    (connect=0.000000 = the egress policy; a fast 403 = RBAC)
[ ] image-pull-secret created; on the regional's SA (its image) and PODPROVISIONER_IMAGE_PULL_SECRET (the workers')
[ ] PODPROVISIONER_IMAGE pinned by digest; PODPROVISIONER_METRICS_INGEST_AUTH from the metrics-ingest-auth Secret
[ ] `GET /api/v1/capabilities` through the ingress shows region, image and capacity.workersFree ≥ the planned fleet
[ ] One-worker run from the hub; then teardown (the run purge deletes the Pods)
```

Traps recorded on the reference deployment: `klogin -a` rewrites the whole
kubeconfig (back it up); a `SocksSocketImpl` frame in a fabric8 stack trace is
the JDK default, not a proxy; `ImagePullBackOff` is a pull-secret problem, never
a NetworkPolicy one (the kubelet pulls).
