# Flow diagrams

How the components interact at runtime. The diagrams carry the detail — the
prose only says what a diagram cannot.

1. [Control plane — starting a run](#1-control-plane--starting-a-run)
2. [Pod registry — register, heartbeat, sweep, claim](#2-pod-registry--register-heartbeat-sweep-claim)
3. [Data plane — metrics from JMeter to Grafana](#3-data-plane--metrics-from-jmeter-to-grafana)
4. [Artifact plane — test plans, data files, results](#4-artifact-plane--test-plans-data-files-results)
5. [Run lifecycle — local-orchestrator state machine](#5-run-lifecycle--local-orchestrator-state-machine)
6. [UI request routing — nginx fan-out](#6-ui-request-routing--nginx-fan-out)
7. [Boot order — `docker compose up`](#7-boot-order--docker-compose-up)
8. [Shutdown order — graceful drain on SIGTERM](#8-shutdown-order--graceful-drain-on-sigterm)

---

## 1. Control plane — starting a run

Dashed lines are sync HTTP; solid lines are persistence.

```mermaid
sequenceDiagram
    autonumber
    participant U  as Browser (UI)
    participant NX as nginx (jmeter-cloud-ui)
    participant GO as global-orchestrator
    participant PG as Postgres<br/>(jmetercloud_globalrun)
    participant RO as regional-orchestrator<br/>(in the region's cluster)
    participant LO as local-orchestrator-N<br/>(JMeter baked in)
    participant JM as JMeter (child process)

    U->>NX: POST /api/v1/runs<br/>{ testPlanBlobId, fleetAllocation:[{region,count}], … }
    NX->>GO: forward (^~ /api/* proxy)

    rect rgba(200,220,255,0.25)
        Note over GO,PG: Atomic claim transaction
        loop once per fleetAllocation entry
            GO->>PG: SELECT … FROM "globalOrchestrator"."pod"<br/>WHERE state='IDLE' AND region=? AND applicationId=?<br/>AND NOT EXISTS (active runFleetMember)<br/>FOR UPDATE SKIP LOCKED LIMIT count
        end
        GO->>PG: INSERT INTO "globalOrchestrator"."run" (state=PREPARING)
        GO->>PG: INSERT "runFleetMember" rows (state=PENDING) per claimed pod
        GO->>PG: UPDATE "run" SET state=STARTING
    end

    alt shortfall and spinShortfall (async launch)
        GO->>PG: INSERT "run" (state=PREPARING,<br/>stateReason='provisioning 6 worker(s) … 0/6 ready')
        GO-->>NX: 201 Created + Run JSON (PREPARING)
        NX-->>U: launch modal stays on "Provisioning workers"
        Note over GO,RO: a virtual thread reserves names serially (PK-guarded),<br/>then creates every pod in parallel and waits for readiness
        GO->>RO: POST /api/v1/pods { podName, applicationId, region } × N
        loop until ready (readiness probe = Tomcat answering)
            GO->>RO: GET /api/v1/pods/{podName}
            GO->>PG: UPDATE "run" SET stateReason='… k/N ready'
        end
        GO->>PG: UPDATE pod SET state='IDLE' (LOST-until-ready → claimable)
        Note over GO,PG: then the same claim transaction as above,<br/>into the existing run
    end

    par bounded-thread-pool fan-out (default 32)
        GO->>RO: POST /api/v1/workers/{podName}/api/v1/test<br/>{ runId, region, testPlanBlobId, … }
        Note over GO,RO: RegionRouter — a region with a URL in REGIONS<br/>is dialled through its relay. A direct region<br/>is dialled at the worker's own baseUrl.
        RO->>LO: POST /api/v1/test (relayed verbatim)
        LO->>JM: spawn /opt/jmeter/bin/jmeter -n -t plan.jmx -l results.jtl
        LO-->>RO: 202 ACCEPTED
        RO-->>GO: 202 ACCEPTED
        GO->>PG: UPDATE "runFleetMember" state=ACCEPTED, fanoutStatusCode=202
    and
        Note over GO,LO: Strict mode rolls the whole claim back on any<br/>per-region shortfall → 503 INSUFFICIENT_CAPACITY.<br/>?bestEffort=true accepts the partial claim.
    end

    GO->>PG: UPDATE "run" state=RUNNING (if any member ACCEPTED)
    GO-->>NX: 201 Created + Run JSON (sync launch) — the async one is already RUNNING by now
    NX-->>U: navigate to /applications/{app}/runs/{runId} once RUNNING

    Note over U,GO: Run detail polls GET /runs/{runId}/status every 5 s.<br/>The global re-polls each pod inline (through the<br/>region's relay) and rolls member states up into the run state.
```

---

## 2. Pod registry — liveness, sweep, claim

Dynamic workers in a routed region never call the hub. The kubelet is the
liveness truth, read once per region per tick:

```mermaid
sequenceDiagram
    autonumber
    participant GO as global-orchestrator<br/>(WorkerLivenessProbe, every 15 s)
    participant RO as regional-orchestrator
    participant K8 as Kubernetes API
    participant PG as Postgres<br/>(globalOrchestrator.pod)

    Note over GO,PG: POST …/capacity/{region}/pods registers the pod LOST<br/>(unclaimable), asks the regional to create it, and waits up to 20 s<br/>for readiness. Slower pods answer ready=false and this loop flips them IDLE.

    loop every 15 s, once per routed region
        GO->>RO: GET /api/v1/workers
        RO->>K8: list Pods (managedBy=regional-orchestrator)
        RO-->>GO: [{ podName, ready, dead, reason, exitCode }]
        alt ready
            GO->>PG: UPDATE pod SET lastHeartbeat=now(), state='IDLE'
        else never ready (LOST, runsServed=0) and dead, absent or Pending for 10 min
            GO->>RO: DELETE /api/v1/pods/{podName}
            GO->>PG: DELETE FROM pod WHERE podId=…
        else dead or absent (OOMKilled, Unschedulable, deleted)
            GO->>PG: UPDATE pod SET state='LOST'
            GO->>PG: UPDATE runFleetMember SET state='FAILED',<br/>stateReason='worker Pod OOMKilled (exit 137)'
        end
    end
    Note over GO: A region unreachable for more than 5 min<br/>loses every dynamic worker in it. Shorter outages change nothing.<br/>A pod that served a run keeps its LOST row for forensics.
```

Operator-declared (static or direct-region) workers keep the heartbeat model:

```mermaid
sequenceDiagram
    autonumber
    participant LO as local-orchestrator pod<br/>(PodRegistrar bean)
    participant GO as global-orchestrator
    participant PG as Postgres<br/>(globalOrchestrator.pod)
    participant SW as PodSweeper @Scheduled<br/>(every 30 s)

    Note over LO: @PostConstruct fires a daemon thread<br/>so Spring init never blocks on the global.

    LO->>GO: POST /api/v1/registerPod<br/>{ podId, region, baseUrl }
    GO->>PG: INSERT INTO pod … ON CONFLICT (podId) DO UPDATE<br/>SET state='IDLE', region=…, baseUrl=…, lastHeartbeat=now()
    GO-->>LO: 200 OK

    loop every 30 s (heartbeatIntervalMs)
        LO->>GO: POST /api/v1/heartbeat { podId }
        GO->>PG: UPDATE pod SET lastHeartbeat=now(), state='IDLE'<br/>WHERE podId=…
        alt pod row missing (DB reset)
            GO-->>LO: 404 POD_NOT_REGISTERED
            LO->>GO: POST /api/v1/registerPod (auto re-register)
        end
    end

    loop every 30 s (sweepIntervalMs)
        SW->>PG: UPDATE pod SET state='LOST'<br/>WHERE state != 'LOST' AND lastHeartbeat < now() - INTERVAL '90 s'<br/>AND NOT (dynamic pod in a routed region)
    end

    Note over GO,PG: Run-launch claim:<br/>SELECT … WHERE state='IDLE' AND NOT EXISTS<br/>(active runFleetMember) ORDER BY lastHeartbeat DESC<br/>LIMIT N FOR UPDATE OF p SKIP LOCKED.<br/>SKIP LOCKED makes concurrent launches pick<br/>different pods rather than waiting on each other.
```

The 90 s LOST threshold is 3× the heartbeat, so one missed beat cannot flap a
pod's state. A LOST pod's next heartbeat returns it to IDLE — no
re-registration needed.

---

## 3. Data plane — metrics from JMeter to Grafana

```
┌──────────────────────────────────────────────────────────────────────┐
│  jmeter-local-orchestrator pod (image bakes JMeter 5.6.3)            │
│                                                                       │
│   JMeter (child)                                                      │
│       │                                                               │
│       │ writes results.jtl (CSV, one row per request,                 │
│       │  user.properties baked in at image build)                     │
│       ▼                                                               │
│   FilePoller ─► JtlRowParser ─► TumblingWindowAggregator              │
│                                  (HDRHistogram per                    │
│                                   {workerId, label, second})          │
│       │                                                               │
│       │ one WorkerMetricBatch envelope per closed second,             │
│       │ per (workerId, windowSecond)                                  │
│       ▼                                                               │
│   AsyncMetricsDispatcher                                              │
│     ├ persists to DiskBackedMetricsBuffer FIRST (gzipped, atomic      │
│     │  rename) — a failed POST just stays on disk for the sweeper     │
│     └ HttpIngestClient POSTs it                                       │
└────────────┬─────────────────────────────────────────────────────────┘
             │
             │ POST /api/v1/ingest   Content-Type: application/json
             │ body: WorkerMetricBatch
             ▼
┌──────────────────────────────────────────────────────────────────────┐
│  jmeter-metrics-consumer  (:8083)                                     │
│                                                                       │
│   ┌─ Jackson decode; required identity fields validated here so a     │
│   │  semantically-broken envelope is a terminal 400, not a 503 loop   │
│   └─ ONE statement per chunk:                                         │
│        WITH "ins" AS (                                                │
│          INSERT INTO metrics."workerMetric" VALUES (…), (…), …        │
│          ON CONFLICT ("runId","workerId","label","windowSecond")      │
│          DO NOTHING                                                   │
│          RETURNING …          ← only rows that ACTUALLY landed        │
│        ) → upsert runSecond / runSecondStatus / runLabel rollups      │
│                                                                       │
│   That RETURNING is the whole correctness argument: the raw insert    │
│   is idempotent but "+= delta" is not, so a replayed envelope adds    │
│   nothing because it inserted nothing.                                │
│                                                                       │
│   202 → worker deletes from its buffer                                │
│   400/413 → terminal, worker drops + counts                           │
│   503 → worker retries from disk (ingest is idempotent)               │
└────────────┬─────────────────────────────────────────────────────────┘
             │
             │ partitioned by ISO week on "windowSecond"
             ▼
┌──────────────────────────────────────────────────────────────────────┐
│  Postgres — jmetercloud_metrics                                       │
│                                                                       │
│   metrics."workerMetric"  (parent partitioned table)                  │
│     ├ workerMetric_2026w19   ← week-19 rows                          │
│     └ … weekly partitions kept 8 weeks ahead by the consumer's        │
│          PartitionMaintenanceJob (boot + daily, advisory-locked)      │
│                                                                       │
│   metrics."runSecond" / "runSecondStatus" / "runLabel"                │
│     └ the rollups every orchestrator read uses                        │
└────────────┬──────────┬──────────────────────────────────────────────┘
             │          │
             │ rollups  │ raw table
             ▼          ▼
   ┌──────────────────┐  ┌──────────────────────┐
   │  orchestrators   │  │  Grafana             │
   │  /timeseries     │  │  perTestLiveMetrics  │
   │  /metrics        │  │  (the ONLY remaining │
   │                  │  │   raw-table reader)  │
   └──────────────────┘  └──────────────────────┘
```

Latency budget across the data plane (target SLO):

| Hop | Local stack | Cloud (Phase 2) |
|-----|-------------|-----------------|
| JTL row → aggregator | < 1 ms | < 1 ms |
| Aggregator → dispatcher queue | sub-microsecond (CAS) | sub-microsecond |
| Dispatcher → disk buffer | < 5 ms (gzip + atomic rename) | < 10 ms |
| POST /ingest → consumer | < 20 ms | < 50 ms |
| Consumer → Postgres | < 5 ms | < 20 ms |
| Postgres → Grafana panel | < 500 ms (panel refresh) | < 2 s |
| **End-to-end** | **< 1 s** | **< 3 s** |

---

## 4. Artifact plane — test plans, data files, results

A worker gets its artifacts one of two ways, set by `ARTIFACT_SOURCE`:
`HTTP_UPLOAD` (the default — pushed to its own `POST /api/v1/testPlan`) or
`DOCUMENT_SERVICE` (pulled by blobId). Either way JMeter reads from local disk.

```mermaid
flowchart LR
    U["Browser / CLI"]

    subgraph "Path A — UI library (cataloguing)"
        U == "POST /api/v1/blob<br/>X-Name X-Type X-Description" ==> NX[nginx]
        NX -- "^~ /api/v1/blob" --> DS[document-service]
        DS -- "Local FS write<br/>(default)" --> LFS["{root}/{shard}/{shard}/{blobId}<br/>+ {blobId}.meta.json"]
        DS -- "PutObject<br/>(-Pcloud)" --> S3[("S3 bucket<br/>SSE-S3 + user-metadata")]
        UI["UI launcher dropdown"] -. "GET /api/v1/blob?type=testPlan" .-> DS
    end

    subgraph "Path B — orchestrator's per-run upload (consumed by JMeter)"
        U == "POST /api/v1/testPlan<br/>(per pod)" ==> LO[local-orchestrator]
        LO -- "stage to BASE_DIR/testPlan/" --> POD["pod local disk"]
        POD --> JM[JMeter child]
        JM -- "writes results.jtl" --> POD
    end

    POD -- "AUTO_UPLOAD_RESULTS=true<br/>(off by default)" --> RU[ResultUploader]
    RU -- "gzip + chunked PUT" --> DS
    RU -- "S3 multipart (cloud)" --> S3
```

The document-service exists so nothing else has to answer "where do bytes
live?" — swapping the backend is one `BlobStore` implementation.

---

## 5. Run lifecycle — local-orchestrator state machine

One pod's state machine. `RunService.refreshAndGet` rolls many of these up into
the run-level state.

```mermaid
stateDiagram-v2
    [*] --> IDLE
    IDLE --> PREPARING: POST /api/v1/test
    PREPARING --> STARTING: artifacts staged,<br/>JMeter spawned
    STARTING --> RUNNING: TailerStateMachine<br/>WAITING_FOR_FILE → RUNNING<br/>(JTL header parsed)
    RUNNING --> DRAINING: JMeter exits<br/>OR sentinel observed
    DRAINING --> COMPLETED: aggregator drained,<br/>final window published,<br/>publisher.flush()
    COMPLETED --> IDLE: next POST /test<br/>(clears results/ + logs/)

    PREPARING --> FAILED: artifact validation /<br/>JMeter spawn failure
    STARTING --> FAILED: JTL never opens /<br/>missing required columns
    RUNNING --> FAILED: fatal I/O error
    DRAINING --> FAILED: ingest unrecoverable

    PREPARING --> ABORTED: DELETE /api/v1/test
    STARTING --> ABORTED: same
    RUNNING --> ABORTED: same (SIGKILL JMeter)
    DRAINING --> ABORTED: same

    FAILED --> IDLE: next POST /test
    ABORTED --> IDLE: next POST /test
```

**The invariant:** a worker owns no durable state beyond the in-flight run's
`results/` and `logs/`. Every restart is a clean slate, and fleet-wide state is
reconstructed from the database.

---

## 6. UI request routing — nginx fan-out

nginx routes every API call to the right backend, so the browser only ever
talks to one origin.

```mermaid
flowchart LR
    B["Browser<br/>http://localhost:8086"]

    subgraph "jmeter-cloud-ui  (nginx + static SPA bundle)"
        NX{nginx routing}
    end

    B == "/<br/>/runs/...<br/>/blobs<br/>(SPA routes)" ==> NX
    NX -- "try_files → index.html" --> SPA[("React SPA bundle<br/>index.html + assets/")]

    B == "/api/v1/blob[/...]<br/>(uploads, listing, download)" ==> NX
    NX -- "proxy_pass<br/>(client_max_body_size 1024m,<br/>proxy_request_buffering off)" --> DS[document-service:8084]

    B == "/api/v1/runs[/...]<br/>/api/v1/pods<br/>/api/v1/registerPod<br/>/api/v1/heartbeat<br/>/actuator/*" ==> NX
    NX -- "proxy_pass" --> GO[global-orchestrator:8082]

    GO -. "fan-out POST /test<br/>+ status poll<br/>+ log proxy" .-> LO[local-orchestrator:8080]

    B -. "deep-link, new tab<br/>(not embedded)" .-> GR[Grafana]
    GR -. "SQL via provisioned<br/>datasource" .-> PG[(Database<br/>jmetercloud_metrics)]
```

**Order matters in `nginx.conf`:** `^~ /api/v1/blob` must precede the general
`/api/` rule, or blob traffic goes to the orchestrator. One image serves both
compose and Kubernetes — `DNS_RESOLVER` and `SVC_SUFFIX` are derived at
container start, because nginx's `resolver` ignores resolv.conf search domains
and in-cluster upstreams must be FQDNs.

Metrics render natively in the UI; Grafana is a deep-link for drill-down, not
an embed.

---

## 7. Boot order — `docker compose up`

Arrows are the literal `depends_on` edges. A failed parent stops everything
below it from starting.

```
postgres (healthy)
   │
   ├─► flyway-migrate (one-shot)
   │      │  Applies metrics V1 (workerMetric partitions + helpers)
   │      │  + globalrun V1 (run, runFleetMember)
   │      │  + globalrun V2 (pod registry table)
   │      │
   │      ├─► metrics-consumer
   │      └─► global-orchestrator

redis (healthy)
   │
   └─► global-orchestrator          // Spring Cache provider

mailhog                              // dev SMTP sink, no dependents

global-orchestrator (healthy) ──┐
                                ├─► jmeter-cloud-ui
document-service (healthy) ─────┘

orchestrator-1 (alive)  ──► PodRegistrar fires async on @PostConstruct
                            and POSTs /api/v1/registerPod to the
                            global. The global doesn't depend on the
                            orchestrator being up — its registry just
                            stays empty until pods register.

multiRegion profile adds:
   └─► orchestrator-2 (parallel to orchestrator-1, different region tag)
```

**Cold-cache time-to-healthy:** ~60 s end-to-end (postgres 5 s,
flyway-migrate 5 s, the Spring Boot apps + UI ~30-60 s in parallel,
grafana 10 s). **Warm cache:** ~30 s.

---

## 8. Shutdown order — graceful drain on SIGTERM

The worker's shutdown hook — the platform's most carefully ordered cleanup.

```
SIGTERM received
   │
   ▼
┌──────────────────────────────────────────────────────────────────┐
│ 1. ingestProbe.close()                                           │
│       Flips /actuator/health → DOWN immediately.                 │
│       K8s Service stops routing new traffic to this pod within   │
│       one probe interval (≤ 2 s).                                │
└──────────────────────────────────────────────────────────────────┘
   │
   ▼
┌──────────────────────────────────────────────────────────────────┐
│ 2. runManager.shutdownGracefully(grace)                          │
│       Blocks while the in-flight test (if any) goes:             │
│         SIGTERM JMeter →                                          │
│         JMeter exits →                                            │
│         write sentinel →                                          │
│         drain pipeline →                                          │
│         publish final window →                                    │
│         terminal state                                            │
│       Tomcat is still up so operators can poll                   │
│       GET /api/v1/test and watch the state transition.           │
│       The state machine calls metricPublisher.flush() at         │
│       end-of-run, so by the time this returns the buffers        │
│       have drained for the last run.                             │
└──────────────────────────────────────────────────────────────────┘
   │
   ▼
┌──────────────────────────────────────────────────────────────────┐
│ 3. metricsDispatcher.close()                                     │
│       Stops the dispatch thread. Anything unsent stays in the    │
│       on-disk buffer for the next process — nothing is lost.     │
│       Must happen AFTER shutdownGracefully, or we would stop     │
│       dispatching while the in-flight run is still producing.    │
└──────────────────────────────────────────────────────────────────┘
   │
   ▼
┌──────────────────────────────────────────────────────────────────┐
│ 4. jmx.close()                                                   │
│       Releases the JMX connector after the pipeline /            │
│       observability path no longer needs it.                     │
└──────────────────────────────────────────────────────────────────┘
   │
   ▼
┌──────────────────────────────────────────────────────────────────┐
│ 5. springCtx.close()                                             │
│       Stops Tomcat (no new requests), then disposes the          │
│       DispatcherServlet. Every @Bean has destroyMethod="" so     │
│       closing the context does NOT double-close the resources    │
│       above — they were already released by the                  │
│       orchestrator-owned lifecycle.                              │
└──────────────────────────────────────────────────────────────────┘
   │
   ▼
JVM exits (Thread.currentThread().join() returns)
```

**The order is load-bearing**, which is why the hook owns it rather than
Spring: the default destroy phase cannot express "health DOWN before drain".

> **Open:** no deregistration call on SIGTERM. The sweeper catches it within
> 90 s, so a planned scale-down shows "LOST" for a couple of minutes.
