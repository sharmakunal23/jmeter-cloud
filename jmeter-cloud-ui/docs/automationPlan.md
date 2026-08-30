# Automation (CRON jobs)

The design record for the platform's scheduled-run feature, which has shipped.
Read the status block for what landed and how it differs from the plan below —
the sections after it are the original design, kept because the reasoning still
explains the shape.

## Status (last updated 2026-05-27)

- **D6-A (backend — `cronJob` table + REST CRUD + scheduler + IT):** ✅ **SHIPPED 2026-05-27.** `ORCH_CRON_JOB` + `ORCH_CRON_JOB_FIRE_HISTORY` (Flyway V2), `CronJobController` (8 endpoints), `CronFireService` (launch via `RunService.startRun`), `CronJobScheduler`. **Scheduler is a DB-claim poller, NOT Quartz** — see the corrected "Scheduler" section below.
- **D6-B (frontend — list / detail pages + create modal + Home wiring):** ✅ **SHIPPED.** IA shell landed 2026-05-13; the live wiring landed 2026-05-27 — `src/api/automation.ts` flipped from the stub to the real client, `CreateScheduleDialog` added, `<AutomationDetailPage>` wired for create/enable/disable/fireNow/delete, Home "Upcoming scheduled runs" reads enabled schedules. `CronJobSummary` field names match the backend verbatim.
- **D6-C (operator UX polish):** the cron-expression "next fires" preview shipped with D6-B (dependency-free, UTC, 5-field). Remaining polish (tz-aware preview, richer fire-history surfacing) is incremental.
- **D6-D (webhook notifications):** ⏳ deferred until there's demand (Achievable addition #10, out of scope for the current Goals-only pass).

## What it does

Operators schedule a saved Template to fire on a CRON expression. When
the schedule fires, the global-orchestrator launches a run from the
template body — same path as `POST /api/v1/runs` from the launcher,
just initiated by the scheduler instead of a human click.

Three operator stories drive the scope:

1. **Nightly regression** — a templated "checkout-svc baseline · 5 pods"
   fires every night at 02:00 UTC; the metrics-consumer's normal
   ingestion pipeline records the run; operators check the dashboard
   in the morning.
2. **Pre-deploy smoke** — a CRON tied to a `release/*` branch's CI
   webhook (out of scope for this design — handled at the CI side via
   the launcher API).
3. **Periodic capacity drill** — a CRON that fires a "sustained 30
   minutes at 80 % of the group's maxAvailable" run weekly, validating the pod fleet
   actually scales to the configured budget without surprises.

## Data model

The table is `ORCH_CRON_JOB` in `CARDZATE_DB_GRAF` (`oracle/migrations/V2__controlPlaneSchema.sql`):
unique `(APPLICATION_NAME, NAME)`, `(ENABLED, NEXT_FIRE_AT)` indexed for the claim
sweep (`ORCH_CLAIMS.CLAIM_DUE_CRON_JOBS`), fire history in `ORCH_CRON_JOB_FIRE_HISTORY`.

`UNIQUE (applicationName, name)` — operators can have a "nightly" CRON
for `checkout-svc` and another `nightly` for `payment-api` without
collision.

## Scheduler — SHIPPED as a DB-claim poller (not Quartz)

**Decision (2026-05-27):** the scheduler is a Spring `@Scheduled` tick that
claims due `cronJob` rows with `SELECT … FOR UPDATE SKIP LOCKED` — the same
idiom `PodSweeper`/`ApplicationHealthPoller` already use. This **overrides** the
original "Quartz, recommended for v1" recommendation below, chosen for the two
stated goals (reliability + smooth AWS move):

- **HA with zero config.** Run N global-orchestrator replicas (e.g. on EKS) and
  the row lock guarantees each due schedule fires exactly once — no leader
  election, no Quartz cluster tables, no double-fire config landmine. This is
  the single biggest "smooth AWS" win and the reason Quartz was rejected.
- **Restart-safe + catch-up-once.** `nextFireAt` is materialised in the database, so
  a fire missed during a deploy fires once on the next tick, then advances to
  the next future slot (never a backlog replay).
- **No new dependency.** Spring's `CronExpression` does the parsing + next-fire.

The original options, kept for archaeology:

1. ~~**Quartz** in global-orchestrator (in-process).~~ Rejected — HA needs the
   11-table cluster + DB leader election, a misconfiguration risk on multi-replica
   EKS; heavier dependency. The poller above gives HA for free.
2. **k8s `CronJob`** per scheduled run — buys HA but needs a controller to keep
   manifests in sync with the table. Not needed: the poller is already HA.
3. **AWS EventBridge** + Lambda — cloud-only, external infra. Still an option for
   a future fully-serverless control plane, but the poller works identically
   local and cloud with no extra infra.

There is no approval flow: a fire draws on the group's pool exactly like a
manual launch, and fails the same way when the pool is short.

## REST surface (sketch)

```
GET    /api/v1/cronJobs                                  # list (filter ?application=)
POST   /api/v1/cronJobs                                  # create
GET    /api/v1/cronJobs/{cronJobId}
PUT    /api/v1/cronJobs/{cronJobId}                      # edit
DELETE /api/v1/cronJobs/{cronJobId}
POST   /api/v1/cronJobs/{cronJobId}/fireNow              # operator-triggered manual fire (skips cron next-fire calc)
POST   /api/v1/cronJobs/{cronJobId}/enable
POST   /api/v1/cronJobs/{cronJobId}/disable
```

`POST /cronJobs` body:

```json
{
  "name": "nightly-checkout-baseline",
  "applicationName": "checkout-svc",
  "templateBlobId": "01J0CHECKOUT0000000000000T",
  "cronExpression": "0 2 * * *",
  "timeZone": "UTC"
}
```

Response 201 with the persisted record. Validation: cron expression
parses (Quartz or unix-cron), application exists in registry, template
exists in document-service, name unique within the application.

## UI

The Automation pages carry:

- **Header** — title + a "+ New schedule" primary CTA → modal that
  picks application → template → cron (with a small "next 5 fires"
  preview computed client-side from the cron expression).
- **List** — table sorted by `nextFireAt` ascending: Name | App |
  Template | CRON | Enabled toggle | Last fired | Next fires | Actions
  (Edit / Disable / Fire now / Delete).
- **Filter by application** — dropdown matches the Documents +
  Templates pattern.
- **Empty state** — "No schedules yet. Pick a template + a cron
  expression to set one up."

Home's "Upcoming scheduled runs" reads
`cronJobsApi.list({ enabled: true, limit: 25 })` sorted by `nextFireAt`
ascending.

## Failure handling

Per fire attempt, four outcomes; record on the row:

- **LAUNCHED** — `POST /api/v1/runs` returned 201; persist `lastFiredRunId`.
- **SKIPPED** — the group's capacity 409 or the application is unhealthy. Log + leave
  a row in a future `cronJobFireHistory` table for audit.
- **FAILED** — backend 5xx or template body malformed. Same audit row.
- **DISABLED** — operator turned the CRON off mid-fire-window; ignore.

Notification path: out of scope for v1. Operators look at the Home
schedule section + the CRON's last-fire status. Future phases: webhook
on FAILED, optional Slack integration.

## Auth

- CRON jobs run under a system identity (e.g. `cron://system`) so
  `run.initiatedBy` is auditable but not tied to a real user.
- `POST /cronJobs` requires the caller to have write access to the
  application (once auth ships).
- The sponsor-approval workflow for capacity is unaffected — a CRON
  asking for more capacity than its application's group has provisioned still 409s
  at run-launch. Operator must request more *before* the CRON window.

## Phasing

- **Backend** — table + REST CRUD + scheduler + IT. ✅ Shipped, with a
  **DB-claim poller rather than Quartz** (see the Scheduler section).
- **Frontend** — list page, create modal, Home wiring. ✅ Shipped.
- **Operator polish** — cron-expression preview, timezone picker, fire-history
  audit table. Partly shipped; the rest is open.
- **Webhook notifications** — deferred until there is demand.
