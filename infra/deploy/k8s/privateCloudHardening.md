# privateCloudHardening — the KUBE-11 checklist

What must be decided and executed when `infra/deploy/k8s/privateCloud`
moves from "builds clean" to "hosts the platform on a real private
cluster." Each section states the decision, the default recommendation,
and the concrete steps. Execute top to bottom at migration; nothing here
blocks local kind work.

Track: KUBE (KUBE-11). Security cross-refs:
the SECURITY track (S-0/D-6, S-2/S-9, S-5/S-11, S-15).

---

## 1. Secrets sourcing (operator decision — pick one before first apply)

Four Secrets exist, all consumed by name (`postgres-credentials`,
`metrics-consumer-credentials`, `global-orchestrator-credentials`,
`grafana-credentials`; exact keys documented in each service's
`kube/overlays/privateCloud/kustomization.yaml` header).

| Option | When it's the right call |
|--------|--------------------------|
| **Plain Secrets, created out-of-band + RBAC** (the documented `kubectl create secret` commands) | Default floor. Smallest moving-parts count; fine for a single-team internal cluster. Pair with RBAC that denies `get secret` to non-admins and enable EncryptionConfiguration (secrets encrypted at rest in etcd). |
| **External Secrets Operator** | The org already runs Vault / a cloud secrets manager. Secrets stay in the manager; rotation propagates without kubectl access. Recommended where available. |
| **sealed-secrets** | The manifests are GitOps-managed and the team wants secrets in git. Adds a controller + key-backup obligation. |

Whatever the choice: **the four Secret names and keys are the contract**
— the Deployments reference them by name, so any backend that
materializes those Secrets works without manifest changes.

Also rotate the **ANTHROPIC_API_KEY** handling: locally it sits in the
repo-root `.env` (rotation is a standing user-owned action item); in the
private cloud it must ONLY exist as the `anthropicApiKey` key of
`global-orchestrator-credentials`.

## 2. Rotate the four initdb `localdev` passwords (execute during cutover)

`postgres/initdb/01_createDatabases.sql` creates `jmetercloud`,
`metricsWriter`, `metricsReader`, `globalOrchestratorWriter` with
password `localdev` (public in this repo). On the private cluster,
immediately after first boot:

```sql
ALTER USER "jmetercloud"              WITH PASSWORD '<new>';
ALTER USER "metricsWriter"            WITH PASSWORD '<new>';
ALTER USER "metricsReader"            WITH PASSWORD '<new>';
ALTER USER "globalOrchestratorWriter" WITH PASSWORD '<new>';
```

Then update the consuming Secrets and restart in this order (each pod
only reads credentials at boot):

| Role | Secret (key) | Restart |
|------|--------------|---------|
| `jmetercloud` | `postgres-credentials` (postgresUser/postgresPassword) | postgres probes use it (`pg_isready`); Flyway Job re-runs use it |
| `metricsWriter` | `metrics-consumer-credentials` | `deployment/metrics-consumer` |
| `metricsReader` | `global-orchestrator-credentials` (metricsReader*) + `grafana-credentials` (metricsReaderPassword) | `deployment/global-orchestrator`, `deployment/grafana` |
| `globalOrchestratorWriter` | `global-orchestrator-credentials` (globalrunWriter*) | `deployment/global-orchestrator` |

## 3. NetworkPolicies (manifest ready — enable when the CNI enforces)

`privateCloud/networkPolicies.yaml` ships the full default-deny set plus
one allow per seam:

- ingress-controller → **ui**:80; ui → **global**:8082 + **document**:8084
- global → each **regional**:8088 (the `REGIONS` URLs — external when the
  data center is another cluster), **postgres**:5432, **redis**:6379,
  api.anthropic.com (443), corporate SMTP. The global never talks to a
  worker or a kube-apiserver directly.
- regional → kube-apiserver (its own cluster), **workers**:8080
- workers → consumer:8083, document:8084 — via **open egress** (the SUT
  address is per-run operator input; tightening = S-5/S-11, deferred).
  Only operator-declared workers also call global:8082 (register/heartbeat);
  workers a regional creates never do.
- consumer → postgres; grafana → postgres; flyway Job → postgres
- everyone → kube-dns:53

It is **commented in the umbrella** because kind's kindnet doesn't
enforce NetworkPolicy (it would be untested dead weight) — uncomment on
a Calico/Cilium/enforcing cluster after filling the two placeholders
(ingress-controller namespace, SMTP egress) and verifying kubelet
probes pass under default-deny on your CNI.

## 4. TLS at the Ingress (SECURITY S-15 lands here)

`jmeter-cloud-ui/kube/overlays/privateCloud/ingress.yaml` carries the
commented `tls:` block. At migration:

1. Set the real hostname + `ingressClassName`.
2. Certificate: **cert-manager** with the org's internal CA issuer
   (recommended — auto-renewal), or a manually-provisioned cert Secret
   (`kubectl create secret tls jmeter-cloud-tls ...`).
3. Force HTTPS (`nginx.ingress.kubernetes.io/ssl-redirect: "true"`) and
   add HSTS once the cert is stable.
4. Revisit the Grafana iframe-embedding envs
   (`GF_SECURITY_ALLOW_EMBEDDING=true`, `COOKIE_SAMESITE=none`,
   `CONTENT_SECURITY_POLICY=false` — compose-parity defaults): once the
   platform hostname exists, scope CSP `frame-ancestors` to it instead
   of disabled-CSP, and consider `SAMESITE=lax`.

Keep the body-size/read-timeout annotations as shipped (1024m/600s) —
they mirror the pod nginx's blob-upload limits.

## 5. storageClass + backup posture

| PVC | Contents | Posture |
|-----|----------|---------|
| `postgres` (StatefulSet template, 10Gi) | ALL platform state: runs, registry, capacity, audit trail, metrics partitions, AI cache | Set an explicit `storageClassName` (SSD-class, `allowVolumeExpansion: true`). **Backups are mandatory**: nightly `pg_dump` CronJob to off-cluster storage, or CSI VolumeSnapshots/Velero on a schedule. Test a restore before go-live. Weekly `workerMetric` partition lifecycle is owned by the metrics-consumer's `PartitionMaintenanceJob` since PARTITION-MAINTENANCE (2026-07-24): boot + daily cron, advisory-lock guarded, `ensureUpcomingPartitions(8)` + `dropOldPartitions(52)` as SECURITY DEFINER functions (V14) — no external cron stopgap needed; retention caps growth at ~52 weeks. |
| `document-service-data` (10Gi) | Test plans, data zips, saved JTL archives | Same storageClass treatment. Backup optional-but-recommended (artifacts are re-uploadable; saved results are not). Growth = operator-driven; alert on PVC usage >80%. |
| grafana (none by default) | Nothing durable (ConfigMap-provisioned) | Only add the commented PVC patch if operators save UI tweaks. No backup obligation. |

Both overlays carry commented `storageClassName` patch stubs
(postgres) — set them rather than relying on the cluster default.

## 6. Alerting obligation (SLIMDOWN D-6, generalized — HARD requirement)

The platform emits **no metrics**: the private
cloud's log/monitoring stack must recreate the four retired abuse/health
thresholds from the services' JSON logs, plus scrape-free health:

| Signal | Source (JSON logs, one object/line) |
|--------|-------------------------------------|
| Rate-limit rejections | global-orchestrator throttled `RATE_LIMITED` WARN |
| Client-error rate | every service's `AccessLogFilter` line (`status` field, 4xx ratio per service) + metrics-consumer `INGEST_BAD_JSON` / `INGEST_TOO_LARGE` WARNs |
| Concurrent runs | queryable from `jmetercloud_globalrun` (`SELECT count(*) FROM run WHERE state='RUNNING'`) — schedule it |
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
`cloud` profile auth work first (PHASE2 Track C; SECURITY S-2/S-9).
NetworkPolicies (§3) and Ingress TLS (§4) are *transport* hardening,
not authentication — they don't move this gate.

## 8. Registry + image pinning (cross-ref KUBE-8)

Per `README.md` "Images & registry (KUBE-8)": every image goes through
the private registry via the overlays' `images:` transformers, and
`PODPROVISIONER_IMAGE` (runtime-stamped, in NO manifest) must be pinned
`repo:tag@sha256:<digest>` — a config re-tag mid-run drains workers
(IMAGE_MISMATCH). Never roll it while runs are active.

## 9. Deferred security work that lands WITH or AFTER this migration

- **S-5/S-6/S-11** (JMX safety scan, SSRF/target-host allowlist) —
  deferred while internal-only; the workers' open-egress policy in §3 is
  the visible reminder.
