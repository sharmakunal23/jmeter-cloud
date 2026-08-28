# jmeter-metrics-consumer

HTTP ingest service on **port 8083** and the platform's only metrics path:
every `jmeter-local-orchestrator` worker POSTs one JSON `WorkerMetricBatch`
per second to `POST /api/v1/ingest`, which lands it in the Oracle `metrics`
schema and folds it into the rollup tables the orchestrators read. Ingest is
idempotent on `(runId, workerId, label, windowSecond)`, so a worker replaying
its disk buffer after an outage is always safe.

![metrics-consumer flow](docs/diagrams/metricsConsumer.svg)

API contract: [`api/openapi.yaml`](api/openapi.yaml) — the canonical wire
schema for the whole platform, pinned by a golden-payload test in this service
and in the worker.
