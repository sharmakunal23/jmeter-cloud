-- V5__applicationRegistry.sql — applied to the jmetercloud_globalrun database.
--
-- D-AppRegistry: applications become first-class entities owned by the
-- global-orchestrator (was: derived from blob X-Application tags via
-- document-service's /applications endpoint). The new registry adds
-- operator-managed metadata (sealId, description, healthEndpoints) and
-- a health-check snapshot updated by ApplicationHealthPoller every
-- ~30s. UI's /applications surface reads from this table.
--
-- Existing `run.application` field remains a free-form string (no FK
-- retrofit) — registered apps and that name string are decoupled so
-- legacy runs keep their data and new runs can target a registered
-- app by name. The launcher's ApplicationPicker reads the registry;
-- runs created from there always carry a registered name.
--
-- Health endpoints are stored as a JSONB array of strings (URLs).
-- Per-endpoint poll results live in `lastHealthDetails` (JSONB) to
-- keep the schema flat — adding per-endpoint columns would require
-- a UNNEST + recompute on every status read.

CREATE TABLE IF NOT EXISTS "globalOrchestrator"."application" (
    "applicationId"          TEXT         NOT NULL PRIMARY KEY,
    -- Operator-facing name. Used by `run.application` and by the
    -- launcher's ApplicationPicker. UNIQUE so two registrations
    -- can't shadow each other on the same name.
    "name"                   TEXT         NOT NULL UNIQUE,
    -- Optional operator-supplied identifier (e.g. internal ticket /
    -- catalog ID). Free-form; no validation beyond length.
    "sealId"                 TEXT,
    "description"            TEXT,
    -- JSONB array of health-check endpoint URLs. NULL or [] → no
    -- polling, status stays UNKNOWN.
    "healthEndpoints"        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    "createdAt"              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Health snapshot — updated by ApplicationHealthPoller. NULL
    -- when never checked (e.g. no endpoints, or app freshly created).
    "lastHealthCheckedAt"    TIMESTAMPTZ,
    -- HEALTHY / DEGRADED / UNHEALTHY / UNKNOWN.
    "lastHealthStatus"       TEXT,
    -- Per-endpoint result snapshot — JSONB array of
    -- {url, statusCode, latencyMs, error?, ok}.
    "lastHealthDetails"      JSONB
);

COMMENT ON TABLE "globalOrchestrator"."application" IS
    'D-AppRegistry — registered application with operator-managed metadata + last health-check snapshot.';

COMMENT ON COLUMN "globalOrchestrator"."application"."healthEndpoints" IS
    'JSONB array of URLs polled every ~30s by ApplicationHealthPoller. Empty → no polling; status stays UNKNOWN.';

COMMENT ON COLUMN "globalOrchestrator"."application"."lastHealthDetails" IS
    'Per-endpoint poll result snapshot — array of {url, statusCode, latencyMs, error?, ok}. NULL when never checked.';

-- B-tree on name supports the lookup-by-name path in the launcher
-- (UI fetches the full list, then filters; this index supports
-- future filter-by-name queries cheaply).
CREATE INDEX IF NOT EXISTS "idx_application_name" ON "globalOrchestrator"."application" ("name");

-- ── GRANTs ──────────────────────────────────────────────────────────
-- Mirrors the V1 / V2 pattern — globalOrchestratorWriter is the per-app
-- DB user the global-orchestrator process connects as.
GRANT SELECT, INSERT, UPDATE, DELETE
    ON "globalOrchestrator"."application"
    TO "globalOrchestratorWriter";

