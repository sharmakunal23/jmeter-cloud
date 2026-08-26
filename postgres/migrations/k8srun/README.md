# k8srun migrations

Run-state DB for **`jmeter-orchestrator-k8s`** (`jmetercloud_k8srun`).

Cloned verbatim from `postgres/migrations/globalrun/` (V1–V25) on
2026-07-19 (K8S-ORCHESTRATOR Phase D-3) and **evolves independently from
here on** — new k8s-orchestrator migrations land here, new
global-orchestrator migrations land in `globalrun/`, and neither side
back-ports automatically.

## Parity with `globalrun/` is now MANDATORY

**Operator direction, 2026-07-27:** `jmeter-orchestrator-k8s` and
`jmeter-global-orchestrator` must carry the same functionality. Every
migration that lands in `globalrun/` lands here too, and vice versa — the
sync may lag until the originating change has passed its smoke and quality
checks, but it is not optional. See
`tools/parityCheck.sh` — run it before calling an orchestrator change done.

> This **supersedes** the earlier note that `V29__podSource.sql` was
> globalrun-only (STATIC-FLEET D9, which scoped static mode to the global
> orchestrator on the grounds that the k8s twin exists *to* provision).
> V29 is now applied to both.

Two deliberate reuse decisions:

- The schema inside the DB keeps the name `globalOrchestrator` — renaming
  it would churn every SQL string in the service for zero behavioral gain.
  The database name (`jmetercloud_k8srun`) is the isolation boundary.
- Grants keep targeting the `globalOrchestratorWriter` role (created in
  `postgres/initdb/01_createDatabases.sql`) for the same reason. Cloud
  deployments get per-service IAM roles at the AWS step.
