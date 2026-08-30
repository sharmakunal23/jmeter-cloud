# oracle

The Oracle Database behind the whole platform — locally an Oracle Database Free
container (`gvenzl/oracle-free:23-slim`), on the target infrastructure the
operator's instance: one PDB with two schemas. **`CARDZATE_DB_GRAF`** is the
metrics schema in the hosted environment's exact layout — the shared
dimensions `LABEL`, `RUN`, `WORKER`, the routing table `GROUP_REGISTRY`, and per
application group the fact tables `<GROUP_ID>_METRICS` (hot) and
`<GROUP_ID>_METRICS_H` (history) with their nightly maintenance job — and
**`"globalOrchestrator"`** is the control plane (runs, pods, applications and
their groups, capacity, automation). The metrics-consumer connects as the
metrics owner; `"metricsReader"`, `"metricsPurger"` and
`"globalOrchestratorWriter"` are the granted users.

![oracle schemas and roles](docs/diagrams/oracle.svg)

| What | Where |
|---|---|
| Shared metrics objects (Flyway `V1`) and each group's bundle (`R__group_<id>.sql`) | [`migrations/metrics/`](migrations/metrics/) |
| Group descriptors + the renderer that produces the bundles | [`groups/`](groups/) |
| Control-plane schema (Flyway `V1`) | [`migrations/globalrun/`](migrations/globalrun/) |
| Owners + users — the DBA hand-off, run once as SYS | [`initdb/`](initdb/) |
| Design and verified gates | [`docs/metricsSchema.md`](docs/metricsSchema.md), [`docs/globalrunSchema.md`](docs/globalrunSchema.md) |

```bash
node groups/renderGroup.mjs --check --all   # every rendered bundle matches its descriptor
docker compose up flyway-migrate            # apply both schemas (repeatables re-run when changed)
```
