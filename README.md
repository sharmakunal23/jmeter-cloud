# jmeter-cloud

JMeter as a service. Upload a `.jmx` plan in the UI, pick how many workers you
want and where, hit **Start** — and watch latency, throughput and
error rate stream in while the test is still running.

The platform runs a fleet of JMeter workers instead of one, so a load test
scales past what a single machine can generate. Each worker owns exactly one
JMeter process; a control plane claims idle workers, fans the run out to them,
and folds their metrics back into one view. It runs on a laptop with
`docker compose up`, and on Kubernetes from the same images.

## How the services fit together

![Service topology and data flow](docs/diagrams/architecture.svg)

The **control plane** is the UI talking to the global-orchestrator, which owns
run state, per-group capacity (an application group owns its worker pool) and the worker registry — and holds no
cluster credential. Each data center runs one stateless
**regional-orchestrator**: it creates that region's worker Pods, reports their
liveness straight from the Pod list, and relays the global's calls to them. The
**data plane** is N worker pods, each running JMeter and shipping metrics of its
own accord. **Storage** is a single database holding both run state and metrics,
with a dashboard alongside for drill-down.

Two things worth knowing: a worker's liveness is **the kubelet's word**, not a
heartbeat — a dead worker's reason (`OOMKilled`, `Unschedulable`) reaches the
run that lost it; and metrics go **straight from worker to metrics-consumer**,
never through an orchestrator, so no control plane is in the metrics hot path.

## How one sample becomes a chart

![Metrics pipeline and the technology at each hop](docs/diagrams/dataFlow.svg)

JMeter writes a CSV row per request. The worker tails that file, folds every
row into grid-aligned 15-second windows with an HDRHistogram, and POSTs one
JSON envelope per window to the consumer with the run's application group
(`?groupId=`). **It writes each envelope to a local disk buffer before sending
it**, so a consumer outage or a network blip costs latency, not data.

The consumer routes each envelope to its group's fact table and inserts with
first-write-wins semantics (`IGNORE_ROW_ON_DUPKEY_INDEX` on the primary key),
so a replayed envelope adds nothing — that is what makes the whole path safe
to retry. There are no rollup tables: readers aggregate at query time, always
by run and window range.
