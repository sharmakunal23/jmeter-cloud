-- 01_createDatabases.sql — runs once on first Postgres container start
-- against an empty data dir. Provisions the cluster-level objects (the
-- two databases and the per-app users) that the Flyway migrations under
-- postgres/migrations/ then populate with schemas, tables, and grants.
--
-- Why two layers (init + Flyway):
--   • Users live ABOVE database scope — a per-database migration can't
--     CREATE USER. So we create them once here.
--   • Schemas + tables + grants are versioned, history-tracked, and
--     repeatable — that's the migration layer's job.
--
-- Cloud equivalent: an EKS one-shot Job + Secrets-Manager-managed
-- credentials.

-- ── Databases ───────────────────────────────────────────────────────
-- POSTGRES_DB env var creates jmetercloud_metrics; we add the others.
CREATE DATABASE jmetercloud_globalrun;
-- K8S-ORCHESTRATOR D-3 — jmeter-orchestrator-k8s owns its own run-state
-- DB (two full orchestrators sharing one would double-fire schedulers and
-- mix pod registries). Migrations: postgres/migrations/k8srun/.
-- NOTE: this file only runs on a FRESH volume — on an existing local
-- instance create it by hand (the jmeter-orchestrator-k8s/k8s/local
-- bootstrap does this idempotently).
CREATE DATABASE jmetercloud_k8srun;

-- ── Per-app users (cluster-level) ───────────────────────────────────
-- Local-only passwords. Cloud uses IAM-DB authentication; the password
-- is set but never used because IAM tokens take precedence.

-- metricsWriter — used by jmeter-metrics-consumer. INSERT on metrics.*
CREATE USER "metricsWriter" WITH PASSWORD 'localdev';

-- metricsReader — used by jmeter-global-orchestrator. SELECT on metrics.*
CREATE USER "metricsReader" WITH PASSWORD 'localdev';

-- metricsPurger — used by jmeter-global-orchestrator's HARD-DELETE / purge
-- path ONLY. SELECT + DELETE on metrics.* (grants live in the metrics Flyway
-- migrations). Deliberately a SEPARATE role from metricsReader (which stays
-- read-only, setReadOnly(true)) and metricsWriter (INSERT-only, the consumer):
-- purge is the one operator-driven action that removes per-run metric rows, so
-- it gets its own least-privilege, purpose-built connection rather than
-- loosening either hot-path role.
CREATE USER "metricsPurger" WITH PASSWORD 'localdev';

-- globalOrchestratorWriter — used by jmeter-global-orchestrator for
-- the run-state DB. INSERT/UPDATE on globalOrchestrator.*
CREATE USER "globalOrchestratorWriter" WITH PASSWORD 'localdev';

-- ── Database-level CONNECT grants ───────────────────────────────────
-- metricsWriter / metricsReader can connect to the metrics DB only.
GRANT CONNECT ON DATABASE jmetercloud_metrics TO "metricsWriter";
GRANT CONNECT ON DATABASE jmetercloud_metrics TO "metricsReader";
GRANT CONNECT ON DATABASE jmetercloud_metrics TO "metricsPurger";

-- globalOrchestratorWriter connects to the globalrun DB — and to the
-- k8srun DB: the k8srun migrations are a clone of globalrun's, whose
-- grants target this role, so jmeter-orchestrator-k8s reuses it rather
-- than forking every grant in the cloned migration set (same local trust
-- domain; cloud gets per-service IAM roles at the AWS step).
GRANT CONNECT ON DATABASE jmetercloud_globalrun TO "globalOrchestratorWriter";
GRANT CONNECT ON DATABASE jmetercloud_k8srun TO "globalOrchestratorWriter";

-- The default jmetercloud user (POSTGRES_USER) keeps superuser-equivalent
-- privileges for ad-hoc DDL and as the Flyway migration user.
GRANT ALL PRIVILEGES ON DATABASE jmetercloud_globalrun TO jmetercloud;
GRANT ALL PRIVILEGES ON DATABASE jmetercloud_k8srun TO jmetercloud;
