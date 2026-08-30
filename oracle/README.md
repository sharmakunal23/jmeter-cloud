# oracle

The Oracle Database behind the whole platform — locally an Oracle Database Free
container (`gvenzl/oracle-free:23-slim`), on the target infrastructure the
operator's instance: one PDB, one schema, **`CARDZATE_DB_GRAF`**. It holds the
hosted environment's metrics layout verbatim — the shared dimensions `LABEL`,
`RUN`, `WORKER`, the routing table `GROUP_REGISTRY`, and per application group
the fact tables `<GROUP_ID>_METRICS` (hot) and `<GROUP_ID>_METRICS_H` (history)
with their nightly maintenance job — and, beside it, the platform's `ORCH_*`
control-plane tables (runs, applications and their groups — each group owning
its worker pool: pods, capacity, pod policy — automation) with the `ORCH_CLAIMS`
package. Every identifier is UPPER_SNAKE and unquoted, usernames included: the
metrics-consumer connects as the owner; `METRICS_READER`, `METRICS_PURGER` and
`GLOBAL_ORCHESTRATOR_WRITER` are the granted users.

![oracle schema and roles](docs/diagrams/oracle.svg)

| What | Where |
|---|---|
| Flyway `V1` (the hosted metrics layout), `V2` (the `ORCH_*` control plane + the users' grants) and each group's bundle (`R__group_<id>.sql`) | [`migrations/`](migrations/) |
| Group descriptors + the renderer that produces the bundles | [`groups/`](groups/) |
| Owner + users — the DBA hand-off, run once as SYS | [`initdb/`](initdb/) |
| Design and verified gates | [`docs/metricsSchema.md`](docs/metricsSchema.md), [`docs/controlPlaneSchema.md`](docs/controlPlaneSchema.md) |

```bash
node groups/renderGroup.mjs --check --all   # every rendered bundle matches its descriptor
docker compose up flyway-migrate            # apply V1, V2 and the bundles as the owner (repeatables re-run when changed)
```
