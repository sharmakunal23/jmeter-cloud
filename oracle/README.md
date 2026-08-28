# oracle

The Oracle Database behind the whole platform — locally an Oracle Database Free
container (`gvenzl/oracle-free:23-slim`), on the target infrastructure the
operator's instance: one PDB with the `metrics` schema (interval-partitioned
`"workerMetric"` fact table plus the rollups every orchestrator read uses) and
the `"globalOrchestrator"` schema (run state, pod registry, capacity,
automation). Access is role-scoped — `"metricsWriter"`, `"metricsReader"`,
`"metricsPurger"`, `"globalOrchestratorWriter"` — and every identifier is
camelCase via double-quoting, because an unquoted name folds to UPPER here.

![oracle schemas and roles](docs/diagrams/oracle.svg)

Schema of record: the Flyway migrations under [`migrations/`](migrations/) —
`metrics/` and `globalrun/`, applied by each schema's owner; design and verified
gates in [`docs/metricsSchema.md`](docs/metricsSchema.md) and
[`docs/globalrunSchema.md`](docs/globalrunSchema.md). Instance-level setup
(owners + users, the DBA hand-off): [`initdb/`](initdb/).
