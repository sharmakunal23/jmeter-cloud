# jmeter-metrics-consumer

HTTP ingest service on **port 8083** and the platform's only metrics path:
every `jmeter-local-orchestrator` worker POSTs one JSON `WorkerMetricBatch`
per window to `POST /api/v1/ingest?groupId=<group>`, and the service routes it
through `GROUP_REGISTRY` into that application group's fact table
(`cps` → `CPS_METRICS` in the `CARDZATE_DB_GRAF` schema) — run, worker and
labels resolved to the shared `RUN`/`WORKER`/`LABEL` dimensions, then a
first-write-wins insert on `(RUN_ID, WORKER_ID, LABEL_ID, WINDOW_SECOND)`, so
a worker replaying its disk buffer after an outage is always safe.

![metrics-consumer flow](docs/diagrams/metricsConsumer.svg)

API contract: [`api/openapi.yaml`](api/openapi.yaml) — the canonical wire
schema for the whole platform, pinned by a golden-payload test in this service
and in the worker. Schema and its rules: `oracle/docs/metricsSchema.md`.
