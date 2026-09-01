# docs/

Cross-service material only. Anything owned by one service lives with that
service — design docs under `<service>/docs/`, its diagram under
`<service>/docs/diagrams/`, embedded by its own README.

| File | Embedded by | Shows |
|------|-------------|-------|
| [`flowDiagrams.md`](flowDiagrams.md) | — | Eight runtime flows: run launch (sync and async), worker liveness and claim, metrics, artifacts, run lifecycle, request routing, boot, shutdown. |
| [`privateCloudMigration.md`](privateCloudMigration.md) | — | Standing the platform up against an Oracle schema that already runs a metrics workload: what to ship, the DBA hand-off, and the procedures for adding a group, an application and a cluster. |
| `diagrams/architecture.svg` | root `README.md` | Service topology, top to bottom — edge, control plane, the regional inside each data center, workers, ingest, storage. |
| `diagrams/dataFlow.svg` | root `README.md`, `RUNBOOK.md` | The metrics pipeline and the technology at each hop. |
| `diagrams/bootOrder.svg` | `RUNBOOK.md` | Compose boot and dependency order. |
