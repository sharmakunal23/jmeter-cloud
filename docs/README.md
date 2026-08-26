# docs/

Cross-service material only. Anything owned by one service lives with that
service — design docs under `<service>/docs/`, its diagram under
`<service>/docs/diagrams/`, embedded by its own README.

| File | Embedded by | Shows |
|------|-------------|-------|
| [`flowDiagrams.md`](flowDiagrams.md) | — | Eight runtime flows: run launch, pod registry, metrics, artifacts, run lifecycle, request routing, boot, shutdown. |
| `diagrams/architecture.svg` | root `README.md` | Service topology — control plane, data plane, data storage. |
| `diagrams/dataFlow.svg` | root `README.md`, `RUNBOOK.md` | The metrics pipeline and the technology at each hop. |
| `diagrams/bootOrder.svg` | `RUNBOOK.md` | Compose boot and dependency order. |
