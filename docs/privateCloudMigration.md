# Private-cloud migration plan

How to stand this platform up against an Oracle schema that **already runs a
metrics workload**, without changing anything that is running. For the operator
doing the cutover, the DBA who owns the schema, and the platform team who owns
the namespaces.

The short answer: nothing running has to change, provided you register
jmeter-cloud under its **own group prefix** and ship only the migrations listed
in [§3](#3-what-to-ship-and-what-to-hold-back). One item in the repo as it
stands would break a live maintenance job — [§2](#2-the-one-destructive-item).

---

## 1. What matches, and what does not

The hosted schema `CARDZATE_DB_GRAF` and this repo were built to the same
contract. Diffed statement by statement:

| Layer | Verdict |
|---|---|
| Shared dimensions — `LABEL`, `RUN`, `WORKER`, `GROUP_REGISTRY`, `RUN_BIU`, `METRICS_H_AUDIT`, `METRICS_MAINTAIN_SHARED` | **Identical.** `V1__metricsSchema.sql` is a no-op on a schema that already has them (every block is guarded on `-955` / `-1408`). |
| Per-group hot fact `<P>_METRICS` | **Identical** — columns, PK, `RELY DISABLE NOVALIDATE` FKs, interval partitioning, the `RUN_LBL` index. |
| Per-group `<P>_ARCHIVE_TO_H`, `<P>_PRUNE_H`, `<P>_MAINTAIN`, stats prefs, `<P>_NIGHTLY_MAINT`, the `GROUP_REGISTRY` MERGE | **Identical** (whitespace aside). |
| Per-group history `<P>_METRICS_H` + `_H_STAGE` | **Differs — we add `RUN_ID`** to the table, the PK and the archive procedure's `GROUP BY`. See §2. |
| `<P>_CLASSIFY_LABEL` | Differs cosmetically: `SUBSTR(label,1,n) = 'TG6'` here, a `REGEXP_LIKE` anchor there. Same results, cheaper. |
| Per-group grants | Ours name `METRICS_READER` / `METRICS_PURGER`; a hosted bundle names its own reader users. Both are `GRANT SELECT` on the same objects — additive, not exclusive. |
| Control plane `ORCH_*` (V2–V9) | **All new.** No name overlaps with the metrics layout. |
| Metrics-consumer wire model, SQL, idempotency, cache model | **Identical**, down to bind order and the `IGNORE_ROW_ON_DUPKEY_INDEX` hint. |
| Kubernetes packaging (`kube/kustomize/**`, `jules.yml`, `Dockerfile.privateCloud`) | Matches the platform blueprint. Placeholders to fill, no structural gaps. |

### Ours-only, all additive

| Addition | Effect on an existing consumer |
|---|---|
| `sumElapsedMs` on the wire entry | Ignored — both readers are tolerant. We use it for an exact `AVG_MS` instead of `avg × n`; the 21 written columns are unchanged. |
| `413 PAYLOAD_TOO_LARGE` on ingest | A code an existing producer never sees. |
| `ALTER TABLE {LABEL,RUN,WORKER} MODIFY CONSTRAINT *_PK RELY` in V1 | No-op. The PKs must already be `RELY`, or the fact tables' `RELY` FKs could not exist (ORA-25158). |

---

## 2. The one destructive item

`R__group_<id>.sql` creates `<P>_METRICS_H` with `RUN_ID` in the primary key.
Against a schema where that table already exists **without** `RUN_ID`:

- the `CREATE TABLE` is swallowed by the `-955` guard, so the table keeps its
  current shape;
- but `CREATE OR REPLACE PROCEDURE <P>_ARCHIVE_TO_H` **succeeds**, and the new
  body selects a column that is not there;
- the next `<P>_NIGHTLY_MAINT` fails. Archiving and pruning stop silently, and
  the hot fact table grows without bound.

Our own readers have the mirror problem: `MetricsTimeseriesRepository` and
`RunMetricsRepository` both issue `SELECT … FROM <hist> WHERE RUN_ID = ?`.
A history table with no run dimension cannot answer "this run, after
`hotDays`" at all — it collapses every run in a window.

**Do not run an existing group's bundle against the hosted database.** Give
jmeter-cloud its own `groupId`, and the conflict disappears: the schema is
built for exactly this, since `GROUP_ID` is a discriminator column on the
shared dimensions, not a table split.

---

## 3. What to ship, and what to hold back

| Ship | Hold back |
|---|---|
| `V1__metricsSchema.sql` — a guarded no-op | Every `R__group_*.sql` for a group you do not own |
| `V2`–`V9` — the `ORCH_*` control plane, all new objects | |
| `R__group_<yourGroupId>.sql` — your own rendered bundle | |

Flyway takes one `-locations`, so the hold-back is a build step: point the Job
at a directory containing only the files above, or delete the others from the
image. Verify before the first run:

```bash
docker run --rm <flywayImage> ls /flyway/sql
```

Flyway will create `flyway_schema_history` in the schema and run `V1`–`V9` from
scratch. `V1` is idempotent, so that is safe. If you would rather it never
touch the shared objects at all, use `-baselineOnMigrate=true
-baselineVersion=1`.

---

## 4. DBA hand-off — additive only

Nothing here alters or drops an existing object.

1. **Three users**, each with `CREATE SESSION` only —
   `METRICS_READER`, `METRICS_PURGER`, `GLOBAL_ORCHESTRATOR_WRITER`. Object
   grants come from the migrations, issued by the owner.
   `oracle/initdb/01_createSchemasAndUsers.sql` is the script, minus its
   `LOCAL DEV ONLY` block. Re-runnable.
2. **Confirm the owner's privileges.** It already has `CREATE TABLE` and
   `CREATE PROCEDURE`; the two to check are **`CREATE TYPE`** (`ORCH_ID_TABLE`)
   and **`CREATE JOB`** (`ORCH_CACHE_REAP_JOB` plus your group's nightly job).
3. **Quota** on the owner's default tablespace for ~15 new `ORCH_*` tables and
   your group's fact tables.
4. **Decide the authentication mode** — see §5.

Reuse the reference's verification queries after the run; the two that matter
most are "the fact table has exactly one unique index, on
`(RUN_ID, WORKER_ID, LABEL_ID, WINDOW_SECOND)`" and "`RUN`/`WORKER`/`LABEL`
still have their unique keys".

---

## 5. Open decisions — settle these before you start

| Decision | Why it matters |
|---|---|
| **Own group prefix** (recommended) vs sharing an existing group | The only choice that touches nothing running (§2). |
| **Our metrics-consumer, or the one already deployed?** | The schema explicitly supports two writers, and our SQL is byte-identical, so the get-or-create races are safe either way. Ours gives per-group control and a `413`; theirs means no new ingest deployment but a 24-hour dimension cache you cannot flush. |
| **Kerberos to Oracle** | All four of our pools (consumer ×1, hub ×3) authenticate with username + password. If the platform's Oracle mandates Kerberos with a proxy client, this is the largest unbudgeted item: keytab or ccache mounted into the pods, `data-source-properties` on every pool, and "connect as owner" becomes "proxy to owner". |
| **Namespace quota for the regional** | The regional asks for 32 CPU / 160 Gi / 200 Gi ephemeral in `kube/kustomize/base/resource-quota.yml`, matched by its `jules.yml` `preDeploy` patch. Negotiate the real grant first, then set both together — they must stay identical or pods stall on `exceeded quota`. |
| **Telemetry egress policy** | The platform blueprint lists a Calico FQDN egress policy for its insights endpoint as a required `base/` resource. We have Calico only in overlays, and only for three of five services. Add it if the agent is mandatory. |

### The co-existence rule we break

`MetricsPurgeRepository` deletes the run's `WORKER` and `RUN` rows during a run
purge. A consumer that cached those surrogate ids will then write orphan facts
or fail on the FK, for as long as its dimension cache lives. Scoped to your own
group's runs this is contained — but if a second consumer also serves your
group, coordinate the purge with a cache expiry or a restart.

---

## 6. Cutover order

1. DBA runs the user-creation script (§4).
2. Build the Flyway image with only the migrations from §3; verify its contents.
3. Run the Flyway Job. Check `flyway_schema_history` shows `V1`–`V9` and your
   `R__group_*` bundle, and that the existing group's objects are untouched:
   `SELECT object_name, last_ddl_time FROM user_objects WHERE object_name LIKE '<EXISTINGPREFIX>%'`.
4. Deploy `jmeter-metrics-consumer`, then `document-service`, then
   `jmeter-global-orchestrator`, then `jmeter-cloud-ui`.
5. Deploy one `jmeter-regional-orchestrator` per cluster.
6. Register the clusters (§9), reserve capacity (§10), create the group (§7)
   and its applications (§8).
7. Smoke test (§11).

`GET /api/v1/platform/health` on the hub is the whole platform's health as one
tree — its Oracle pools, the consumer, the document-service, and each data
centre's regional with its worker counts. Read only that; never probe a
regional or a data-plane service directly.

---

## 7. Adding an application group

A group is a team's set of applications. Its `groupId` is what workers send as
`?groupId=` and, upper-cased, the prefix of its fact tables — so **the database
objects must exist before the control-plane row is useful**, and the two ids
must be the same string.

Do these in order. Steps 1–3 are the database; step 4 is the control plane.

**1. Write the descriptor** — `oracle/groups/<groupId>.json`:

```json
{
  "groupId": "payments",
  "name": "Payments",
  "applications": [
    { "name": "PAYMENTS-API", "labelPrefixes": ["PA"] },
    { "name": "PAYMENTS-BATCH", "labelPrefixes": ["PB"] }
  ],
  "unclassified": "OTHER",
  "hotDays": 7,
  "historyDays": 30,
  "readers": ["METRICS_READER"],
  "purgers": ["METRICS_PURGER"],
  "maintenance": { "timeZone": "America/New_York", "fromHour": 4, "toHour": 7 }
}
```

| Field | Get this wrong and… |
|---|---|
| `groupId` | `[a-z][a-z0-9_]{0,29}`, immutable. It is the routing key on every metrics POST *and* the table prefix — a rename means new tables. |
| `applications[].name` | This is the `LABEL.APPLICATION` value the classifier writes. It must equal each application's `metricsApplication` (§8), or the Grafana `application` filter and the per-application views come back empty. |
| `labelPrefixes[]` | First match wins, in list order. Put the more specific prefix first (`TG6` before `TG`). |
| `readers[]` | Add every Grafana database user that must read this group, alongside `METRICS_READER`. A missing user here is a dashboard that renders nothing. |
| `hotDays` / `historyDays` | Feed the nightly job's arguments. `hotDays` must also match the group's `hotDays` in step 4, or the UI picks the wrong Grafana dashboard for a run. |

**2. Render and check in the output** — the rendered SQL is committed, because
the DBA runs the same file the local Flyway does:

```bash
node oracle/groups/renderGroup.mjs --all
node oracle/groups/renderGroup.mjs --grafana --all
node oracle/groups/renderGroup.mjs --check --all            # fails on drift
```

Never hand-edit `oracle/migrations/R__group_<groupId>.sql` or an object it
owns. Change the descriptor and re-render.

**3. Apply it.** Locally `docker compose up flyway-migrate` (repeatables re-run
when the file changes). On the hosted database the DBA runs the same file. It
creates `<P>_METRICS`, `<P>_METRICS_H` (+ `_STAGE`), `<P>_CLASSIFY_LABEL`,
`<P>_ARCHIVE_TO_H`, `<P>_PRUNE_H`, `<P>_MAINTAIN`, the job `<P>_NIGHTLY_MAINT`,
the grants, and the `GROUP_REGISTRY` row that makes `?groupId=` route.

**4. Create the control-plane row** — UI *Manage groups*, or:

```bash
curl -X POST "$HUB/api/v1/applicationGroups" -H 'Content-Type: application/json' -d '{
  "groupId": "payments",
  "name": "Payments",
  "hotDays": 7,
  "grafanaLiveUrl": "https://<grafana>/d/paymentsProductMetrics/...",
  "grafanaHistoryUrl": "https://<grafana>/d/paymentsProductMetricsHistory/...",
  "recyclePolicy": "REUSE",
  "alwaysOn": false
}'
```

`groupId` here **must equal** `GROUP_REGISTRY.GROUP_ID` from step 3. It is
immutable afterwards.

**5. Import the two dashboards.** `--grafana` renders
`oracle/groups/grafana/<groupId>Live.json` and `<groupId>History.json`; the
Oracle datasource is an import input, so Grafana's dialog asks for it. Paste
each resulting URL back into the group's `grafanaLiveUrl` / `grafanaHistoryUrl`.

**6. Attach clusters and reserve capacity** — §9 and §10. A group with no
reservation can run nothing.

### Verify the group

```sql
SELECT * FROM GROUP_REGISTRY WHERE GROUP_ID = 'payments';
SELECT job_name, enabled, next_run_date FROM user_scheduler_jobs
 WHERE job_name = 'PAYMENTS_NIGHTLY_MAINT';
```

```bash
curl -s "$HUB/api/v1/applicationGroups/payments" | jq
```

---

## 8. Adding an application

Registration is a pure database write — no capacity, no pods. Capacity belongs
to the group.

```bash
curl -X POST "$HUB/api/v1/applications" -H 'Content-Type: application/json' -d '{
  "name": "payments-api",
  "metricsGroupId": "payments",
  "metricsApplication": "PAYMENTS-API",
  "sealId": "<yours>",
  "healthEndpoints": ["https://payments-api.<domain>/actuator/health"]
}'
```

| Field | Rule |
|---|---|
| `name` | `[a-z0-9]([-a-z0-9_]{0,62}[a-z0-9])?`, unique. It seeds worker pod names, so keep it DNS-friendly. |
| `metricsGroupId` | Required, and the group must already exist (400 otherwise). |
| `metricsApplication` | Defaults to the upper-cased `name`. **Set it explicitly** unless the default happens to equal one of the descriptor's `applications[].name` values — a mismatch produces an application the classifier never labels. |
| `healthEndpoints` | Max 8 http(s) URLs. Feeds the app's health badge and any workflow `HEALTH_CHECK` gate — both share one prober, so they can never disagree. |

Checklist before you call it done:

- [ ] `metricsApplication` appears in the group descriptor's `applications[]`.
- [ ] The test plan's sampler labels start with one of that entry's
      `labelPrefixes`. Labels that match nothing land in `unclassified`.
- [ ] The group has a reservation on at least one cluster (§10).

---

## 9. Adding a cluster, and confirming connectivity

A cluster is registered at runtime and stored in `ORCH_REGION` — there is no
environment variable and no restart. **Every registered cluster fronts a
`jmeter-regional-orchestrator`**; the hub holds no cluster credential and
reaches the substrate only through it.

### Before you register

1. Deploy `jmeter-regional-orchestrator` into the cluster's namespace, with:
   - a ServiceAccount bound to a **namespaced** Role granting `pods`
     (`create, delete, get, list, watch`), `pods/log` (`get`) and
     `resourcequotas` (`get, list`) — `kube/kustomize/base/rbac.yml`;
   - `REGION` set to the id you will register;
   - `PODPROVISIONER_NAMESPACE` set to **that same namespace** (the Role is
     namespaced, so a worker created anywhere else is `Forbidden`);
   - `PODPROVISIONER_IMAGE` pointing at a worker image the cluster's nodes can
     pull, and `PODPROVISIONER_IMAGE_PULL_SECRET` if the registry is private.
     Image pulls are the kubelet's, so no egress policy of yours affects them.
2. **The Calico egress policy** in
   `kube/kustomize/overlays/<env>/network-policy-custom.yml`. The namespace
   inherits a platform tier that default-denies egress and is invisible to you,
   so this is mandatory, and Calico evaluates egress **after kube-proxy's
   DNAT** — a rule naming the `kubernetes.default` ClusterIP never matches.
   Allow the control-plane **endpoint** IPs:

   ```bash
   kubectl get endpoints kubernetes -n default   # per cluster; they differ
   ```

   Fill those into the `REPLACE_ME_APISERVER_ENDPOINT_IP_*` placeholders, plus
   DNS on 53. Confirm from inside the pod — `HTTP=200` is good, `HTTP=000` with
   `connect=0.000000` is still blocked:

   ```bash
   kubectl exec <regionalPod> -n <ns> -- sh -c '
     T=$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)
     curl -sS -m 15 -o /dev/null -w "HTTP=%{http_code} connect=%{time_connect}\n" \
       --cacert /var/run/secrets/kubernetes.io/serviceaccount/ca.crt \
       -H "Authorization: Bearer $T" \
       https://kubernetes.default.svc/api/v1/namespaces/<ns>/pods'
   ```
3. **Read the namespace's real limits** before choosing `maxWorkers`:

   ```bash
   kubectl get resourcequota -n <ns>
   kubectl get limitrange -n <ns> -o yaml
   ```

   | What you may find | What to do |
   |---|---|
   | `requests.cpu: 0`, `requests.memory: 0` | The quota forbids cpu/memory keys entirely. Set `PODPROVISIONER_CPU_MEMORY_RESOURCES=false` and bound the JVM with `PODPROVISIONER_WORKER_JAVA_OPTS` instead. |
   | `pods: 20` | That ceiling includes the regional's own pod, so the real worker ceiling is one less. |
   | `maxLimitRequestRatio: 1` on ephemeral-storage | Already honoured — the provisioner binds one value as both request and limit. Keep `PODPROVISIONER_WORKER_EPHEMERAL_STORAGE` under the LimitRange max. |
   | The `jules.yml` quota patch was rejected | Everything above applies; the patch is what makes cpu/memory usable at all. |

### Register

UI: *Capacity → Clusters → + Add cluster*. Or:

```bash
curl -X POST "$HUB/api/v1/regions" -H 'Content-Type: application/json' -d '{
  "region": "na-east",
  "label": "NA East",
  "regionalUrl": "https://jmeter-regional-orchestrator.<clusterHost>",
  "maxWorkers": 20
}'
```

| Field | Rule |
|---|---|
| `region` | `[a-z0-9]([-a-z0-9]{0,18}[a-z0-9])?`. This is the placement axis everywhere in the API and the schema — "Cluster" is only the UI's display word. |
| `label` | Unique. Two clusters must never present as one. |
| `regionalUrl` | Unique — one regional serves exactly one cluster. Must be reachable from the hub. |
| `maxWorkers` | 1–20; 20 is both the default and the hard cap. The sum of all groups' reservations on this cluster can never exceed it. |

Nothing is written unless the whole validation chain passes: the endpoint
answers `/api/v1/capabilities`, reports the **same** region id, and its
`/api/v1/provisioningCheck` dry run proves it can create worker Pods (image
configured, RBAC verbs proven by SelfSubjectAccessReview, quota headroom ≥ 1).

| Response | Meaning |
|---|---|
| `201` | Registered. The body's `checks` array is your ✓ checklist. |
| `409` | `CLUSTER_EXISTS`, `CLUSTER_NAME_TAKEN` or `CLUSTER_URL_TAKEN`. |
| `422` | A check failed — `code` names the first failure (`INVALID_CLUSTER_URL`, `CLUSTER_UNREACHABLE`, `REGION_MISMATCH`, `NO_WORKER_IMAGE`, `RBAC_DENIED`, `QUOTA_EXHAUSTED`) and `checks` carries the detail for all of them. Nothing was written. |

### Confirm connectivity

Registration proves the hub can reach the regional. **`testProvision` proves
the regional can actually run a worker** — it spins one real probe worker,
waits for the kubelet to report it ready (180 s budget), deletes it, and
records the verdict on the cluster row. It is asynchronous by design, so poll:

```bash
curl -X POST "$HUB/api/v1/regions/na-east/testProvision"      # 202 {"probing": true}
curl -s "$HUB/api/v1/regions/status" | jq '.[] | {region, reachable, lastProbe, reservedWorkers, provisionedWorkers, capabilities}'
```

`reachable` is `null` until the first probe, then `true`/`false` with
`lastSeenAt` / `lastError`. `capabilities` carries the regional's worker image,
footprint and quota headroom — the fastest way to catch an image or quota
mismatch. Then check the whole tree:

```bash
curl -s "$HUB/api/v1/platform/health" | jq
```

Deregistering (`DELETE /api/v1/regions/{region}`) is refused with `409
CLUSTER_IN_USE` while any group reservation or worker row still points at the
cluster — release those first.

---

## 10. Reserving capacity for a group

Registering a cluster makes it available; a group still has to reserve on it.

```bash
curl -X PUT "$HUB/api/v1/applicationGroups/payments/capacity/na-east" \
  -H 'Content-Type: application/json' -d '{"maxAvailable": 8}'
```

Three invariants, serialised per cluster:

| Refusal | Meaning |
|---|---|
| `404 CLUSTER_NOT_REGISTERED` | Register the cluster first (§9). |
| `409 GROUP_CLUSTER_LIMIT` | A group holds at most `maxClustersPerGroup` clusters (default 2). |
| `409 CLUSTER_CAPACITY_EXCEEDED` | The sum of every group's reservations must fit the cluster's `maxWorkers`. The body carries `maxWorkers`, `reservedByOthers` and `requested`. |
| `409 CAPACITY_SHRINK_BELOW_PROVISIONED` | You are reserving less than is already running. Drain first. |

Spun and declared workers both consume the reservation. Spin one with `POST
…/capacity/{region}/pods`; workers you deploy yourself are declared with `PUT
…/capacity/{region}/pods/{podName}` — see `infra/deploy/k8s/staticFleet.md`,
which also covers why a declared worker is never reconciled away or recycled.

`GET /api/v1/applicationGroups/capacitySummary` answers the whole matrix from
the control plane's own tables and touches no cluster; use it for any list
view, and the per-region call only for a drill-in.

---

## 11. Verifying the first run end to end

1. Register a plugin-free application in the group (§8) and upload a small test
   plan whose sampler labels match a `labelPrefixes` entry.
2. Launch a 2-minute run on one worker.
3. While it runs: the UI's Metrics tab should show 15-second points. If it is
   empty, check in this order —

   | Check | Command |
   |---|---|
   | Envelopes are leaving the worker | worker log line `flush… POSTed N labels` |
   | The consumer accepted them | `202` with `rowsInserted > 0`. `rowsInserted: 0` is **success** — it means a replay was suppressed, never an error. |
   | The group routes | `SELECT * FROM GROUP_REGISTRY WHERE GROUP_ID = '<groupId>'` |
   | The dimensions resolved | `SELECT DISTINCT GROUP_ID FROM RUN` — the values are the **`TABLE_PREFIX`**, not the producer-facing `groupId` |
   | Facts landed | `SELECT COUNT(*) FROM <P>_METRICS WHERE RUN_ID = (SELECT RUN_ID FROM RUN WHERE RUN_KEY = '<runId>')` |
   | Labels classified | `SELECT LABEL_KEY, APPLICATION FROM LABEL WHERE GROUP_ID = '<PREFIX>'` |
4. After the run, open the group's Grafana dashboard from the run page and
   confirm the time range and `application` filter carry through.
5. The morning after, confirm the nightly job ran:
   `SELECT * FROM METRICS_H_AUDIT WHERE TABLE_PREFIX = '<PREFIX>' ORDER BY UPDATED_AT DESC`.

`mvn test -PdbTests` in `jmeter-metrics-consumer` and
`jmeter-global-orchestrator` is the machine check for anything that touches a
repository, a migration or a package. It migrates an **empty** schema, so a
migration that only fails once there is data passes it — seed a row and re-run
when a migration changes a populated column.

---

## 12. Rollback

Everything this platform adds is additive, so rolling back is deletion, not
repair.

| To undo | Do |
|---|---|
| A group's database objects | Drop `<P>_*` and delete its `GROUP_REGISTRY` row. The shared dimensions keep rows tagged with that prefix; they are inert. |
| The control plane | Drop the `ORCH_*` tables, the `ORCH_CLAIMS` package, `ORCH_ID_TABLE`, and `ORCH_CACHE_REAP_JOB`. |
| Flyway's own state | Drop `flyway_schema_history`. |
| A cluster | Release reservations and workers, then `DELETE /api/v1/regions/{region}`. Declared workers keep running — the platform only forgets them. |

No hosted object is modified at any point, so there is nothing to restore.
