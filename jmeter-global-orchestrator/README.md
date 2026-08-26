# jmeter-global-orchestrator

The control plane on **port 8082**: owns the application registry, per-(app,
region) capacity and run state in `jmetercloud_globalrun`, claims IDLE pods with
`SELECT … FOR UPDATE SKIP LOCKED`, and fans a run out to many workers. It backs
the UI and provisions workers through either substrate — docker socket (the
compose default) or fabric8 — selected by `PODPROVISIONER_SUBSTRATE`.

> **This service and `jmeter-orchestrator-k8s` are two deployments of one
> control plane and must carry identical functionality.** Any change here is
> mirrored there with `./tools/portToTwin.sh`, and `./tools/parityCheck.sh` must
> exit 0 before it is done. Never blanket-rename `globalOrchestrator` — four
> names say "global" in both services on purpose. See `tools/README.md`.

![global-orchestrator flow](docs/diagrams/globalOrchestrator.svg)

API contract: [`api/openapi.yaml`](api/openapi.yaml) — browsable at
<http://localhost:8082/swagger-ui.html>.
