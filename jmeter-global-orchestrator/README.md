# jmeter-global-orchestrator

The control plane on **port 8082**: owns the application registry, per-(app,
region) capacity, run state and the pod registry in `jmetercloud_globalrun`,
claims IDLE pods with `SELECT … FOR UPDATE SKIP LOCKED`, and fans a run out to
many workers. It holds no cluster credential: under `PROVISIONING_MODE=DYNAMIC`
it creates and reaches workers through each region's
`jmeter-regional-orchestrator` (`REGIONS`), and directly by `baseUrl` in a
region without one.

![global-orchestrator flow](docs/diagrams/globalOrchestrator.svg)

API contract: [`api/openapi.yaml`](api/openapi.yaml) — browsable at
<http://localhost:8082/swagger-ui.html>.
