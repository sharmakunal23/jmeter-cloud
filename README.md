# jmeter-cloud

JMeter as a service. Upload a `.jmx` plan in the UI, pick how many workers you
want and where, hit **Start** — and watch per-second latency, throughput and
error rate stream in while the test is still running.

The platform runs a fleet of JMeter workers instead of one, so a load test
scales past what a single machine can generate. Each worker owns exactly one
JMeter process; a control plane claims idle workers, fans the run out to them,
and folds their metrics back into one view. It runs on a laptop with
`docker compose up`, and on Kubernetes from the same images.

## How the services fit together

![Service topology and data flow](docs/diagrams/architecture.svg)

The **control plane** is the UI talking to the global-orchestrator, which owns
run state, per-application capacity and the worker registry. The **data plane**
is N worker pods, each running JMeter and shipping metrics of its own accord —
workers never wait on the orchestrator to collect anything. **Storage** is a single
database holding both run state and metrics, with a dashboard alongside for
drill-down.

Two things worth knowing: workers **self-register and heartbeat**, so the
orchestrator discovers its fleet rather than being told about it; and metrics go
**straight from worker to metrics-consumer**, never through the orchestrator, so
the control plane is never in the metrics hot path.

## How one sample becomes a chart

![Metrics pipeline and the technology at each hop](docs/diagrams/dataFlow.svg)

JMeter writes a CSV row per request. The worker tails that file, folds every
row into one-second windows with an HDRHistogram, and POSTs one JSON envelope
per second. **It writes each envelope to a local disk buffer before sending it**,
so a consumer outage or a network blip costs latency, not data.

The consumer lands the rows and maintains its rollup tables *in the same SQL
statement* — the insert's `RETURNING` feeds the rollups, so only rows that
actually landed are counted and a replayed envelope adds nothing. That is what
makes the whole path safe to retry.
