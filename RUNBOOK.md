# RUNBOOK — operating jmeter-cloud locally

How to bring the platform up, run a test, watch it, and tear it down. For *what the
platform is* and how the pieces fit together, see [`README.md`](./README.md).

> **TL;DR**
> ```bash
> cp .env.example .env          # one-time; fill ANTHROPIC_API_KEY only if you want AI features
> docker compose up -d --build  # full stack
> open http://localhost:8086    # the UI
> docker compose down           # stop (keep data)   ·   add -v to wipe volumes
> ```

---

## 1. Prerequisites

| Need | Why |
|------|-----|
| Docker Engine + Compose v2 (`docker compose`, not `docker-compose`) | Everything runs as containers, composed from per-subsystem fragments via `include:`. |
| ~6 GB free RAM for Docker | Kafka + Postgres + 4 Spring services + Grafana/Prometheus + worker pods. |
| Ports free on the host | See the port table below — mainly `3000`, `5432`, `8081–8086`, `9090`, `9092`, `16686`. |

No JDK, Maven, or Node is required to *run* the stack — images build inside Docker. You only
need those to develop a single service outside its container.

## 2. One-time setup

```bash
cp .env.example .env
```

`.env` is git-ignored and holds local config. Everything in it has a working local default.
The only secret is `ANTHROPIC_API_KEY` — **leave it blank to keep the AI-analysis features
disabled**; paste an Anthropic key to enable them. The Postgres/Grafana passwords are
throwaway local credentials, fine as-is.

## 3. Bring the stack up

```bash
# Full stack (default profile): kafka + schema-registry + postgres + the 4 services + UI + observability
docker compose up -d --build
```

First build is ~5 min cold; subsequent starts ~30 s. Compose starts things in dependency
order and waits on health checks, so a one-shot `up` is safe — services that depend on Kafka
or Postgres wait until those are healthy.

![Boot order](./docs/diagrams/bootOrder.svg)

**Variants:**

```bash
docker compose --profile minimal up -d --build      # Kafka core only (broker + schema-registry + topic-init)
docker compose up -d --build kafka postgres flyway-migrate   # just the data substrate
docker compose ps                                   # health of every container
docker compose logs -f global-orchestrator          # follow one service's logs
```

Worker pods (`local-orchestrator`) are **not** started by Compose. They are provisioned on
demand per application from the UI **Capacity** tab (or the capacity API). That is by design —
the global-orchestrator owns worker lifecycle so pods can scale per (app, region).

## 4. Service endpoints & ports

| Service | URL | Health check |
|---------|-----|--------------|
| **UI** | http://localhost:8086 | `GET /healthz` |
| global-orchestrator | http://localhost:8082 | `GET /actuator/health` |
| metrics-consumer | http://localhost:8083 | `GET /actuator/health` |
| document-service | http://localhost:8084 | `GET /actuator/health` |
| Grafana | http://localhost:3000 | login `admin` / `admin` (from `.env`) |
| Prometheus | http://localhost:9090 | `/-/healthy` |
| Jaeger (traces) | http://localhost:16686 | — |
| Kafka UI | http://localhost:8085 | — |
| Schema Registry | http://localhost:8081 | `GET /subjects` |
| Kafka broker | `localhost:9092` (host) / `kafka:29092` (in-network) | — |
| Postgres | `localhost:5432` (`jmetercloud` / `localdev`) | `pg_isready` |
| MailHog (dev SMTP) | http://localhost:8025 | — |

Ports are configurable in `.env`; the canonical allocation lives in
[`jmeter-local-orchestrator/README.md`](./jmeter-local-orchestrator/README.md) (Network Ports).

## 5. Run your first test

Easiest path is the UI:

1. Open **http://localhost:8086**.
2. **Capacity** → pick/register an app → add a worker for a region (provisions a `local-orchestrator` pod).
3. **Documents** → upload a `.jmx` test plan.
4. **Runs → New run** → choose the plan, set fleet size/region → **Start**.
5. You land on `/runs/{runId}` with the live fleet table, the native uPlot **Metrics** tab,
   and per-pod logs. Metrics appear within ~1–2 s of the first sample.

Prefer headless? Drive the same flow from each service's Swagger UI
(e.g. document-service at http://localhost:8084/swagger-ui.html to
upload, global-orchestrator at http://localhost:8082/swagger-ui.html to
launch and poll).

The metric pipeline behind that run:

![Metrics data flow](./docs/diagrams/dataFlow.svg)

## 6. Watch it

- **Live per-run charts:** the UI run-detail **Metrics** tab (Postgres-backed uPlot), or Grafana's
  `perTestLiveMetrics` dashboard at http://localhost:3000 (the `runId` variable is auto-populated).
- **Infra dashboards:** orchestrator JVM, JMeter JVM, Kafka broker, Postgres (all provisioned).
- **Traces:** Jaeger at http://localhost:16686 (critical `/api/v1/**` paths only).
- **Logs:** `docker compose logs -f <service>` — JSON, one record per line, each carrying
  `traceId` / `runId` / `actor`.

## 7. Tear down

```bash
docker compose down        # stop + remove containers; Postgres/Kafka volumes PERSIST
docker compose down -v     # also delete volumes — full reset (wipes all runs + metrics)
docker compose stop        # pause without removing containers
```

To rebuild one service after a code change:

```bash
docker compose up -d --build global-orchestrator
```

## 8. Troubleshooting

| Symptom | Likely cause / fix |
|---------|--------------------|
| A service is `unhealthy` in `docker compose ps` | Check its logs: `docker compose logs <svc>`. Java services have `restart: unless-stopped` and self-heal; one-shot jobs (`topic-init`, `flyway-migrate`) should exit `0`. |
| Workers go `unreachable` mid-run after a rebuild | Rebuilding `jmeter-local-orchestrator:dev` while a run is live triggers an image-mismatch drain by the PodRecycler — looks like an OOM but isn't. Don't rebuild the worker image during a run. |
| No metrics on the chart | Confirm `metrics-consumer` is healthy and the run actually claimed a pod (Capacity tab shows BUSY). Check `kafka-ui` (http://localhost:8085) for traffic on `jmeter.metrics.perSecond`. |
| Port already in use on `up` | Edit the offending `*_PORT` in `.env` and re-run `docker compose up -d`. |
| AI tabs missing in the UI | `ANTHROPIC_API_KEY` is blank in `.env` (expected default). Paste a key and restart `global-orchestrator`. |
| Want a clean slate | `docker compose down -v` then `docker compose up -d --build`. |

## 9. Where to go next

- System map & architecture diagram → [`README.md`](./README.md)
- Per-subsystem behavior → each subsystem's own `README.md`
