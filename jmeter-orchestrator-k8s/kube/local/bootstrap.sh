#!/usr/bin/env bash
# K8S-ORCHESTRATOR Phase F — local kind environment for jmeter-orchestrator-k8s.
#
#   ./bootstrap.sh          # full bring-up: cluster + network join + DB +
#                           # migrations + images + manifests + bridge
#   ./bootstrap.sh bridge   # re-sync ONLY the compose-bridge Services
#                           # (run after `docker compose up -d` — container
#                           # IPs change across compose restarts)
#   ./bootstrap.sh down     # delete the kind cluster (compose stack untouched)
#
# Prereqs: the docker-compose stack is up (metrics-consumer, postgres,
# redis, document-service) and `jmeter-local-orchestrator:dev` is
# built (docker compose builds it).
#
# KUBE-8 (2026-07-24): manifests moved to kube/{base,overlays} (kustomize,
# platform convention) and everything now deploys into the ISOLATED
# `jmeter-orchestrator-k8s` namespace — the umbrella stack
# (infra/deploy/k8s/) owns `jmeter-cloud` on the same cluster, and the
# two must not share Service names (workers, postgres, redis, ...).
set -euo pipefail

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
projectDir="$(cd "$scriptDir/../.." && pwd)"          # jmeter-orchestrator-k8s/
repoRoot="$(cd "$projectDir/.." && pwd)"              # jmeter-cloud/

cluster="jmeter-cloud"
node="${cluster}-control-plane"
network="${COMPOSE_NETWORK:-jmeter-cloud_default}"
namespace="jmeter-orchestrator-k8s"
kubectlCtx="kind-${cluster}"
pgUser="${POSTGRES_USER:-jmetercloud}"
pgPassword="${POSTGRES_PASSWORD:-localdev}"

# Compose services worker pods / the orchestrator must reach, as
# {composeServiceName}:{port}. The bridge mirrors each as an in-cluster
# Service + manual Endpoints with the SAME DNS name, so in-cluster clients
# use the familiar compose URLs (metrics-consumer:8083, document-service:8084, ...).
# (jaeger deliberately absent — K8S-SLIMDOWN removed tracing from this service.)
# DIRECT-METRICS (2026-07-20): kafka:29092 + schema-registry:8081 left the
# bridge — workers POST metrics straight to metrics-consumer:8083. Keeping
# the kafka bridge OUT is deliberate: a run completing with full metrics
# while Kafka is unreachable from the cluster is proof-by-construction that
# the HTTP path carried the data.
# mailhog added in KUBE-8: previously it resolved only via the kind
# node's upstream-DNS fallback into docker's embedded DNS — worked by
# luck, and only on kind. Bridging it makes SMTP resolution explicit.
bridgeDeps=(metrics-consumer:8083 document-service:8084 postgres:5432 redis:6379 mailhog:1025)

k() { kubectl --context "$kubectlCtx" "$@"; }
composeCid() { (cd "$repoRoot" && docker compose ps -q "$1"); }

bridge() {
  echo "── F-2: syncing compose-bridge Services (network=$network) ──"
  for dep in "${bridgeDeps[@]}"; do
    svc="${dep%%:*}"; port="${dep##*:}"
    cid="$(composeCid "$svc")"
    if [[ -z "$cid" ]]; then
      echo "WARN: compose service '$svc' is not running — skipping its bridge (start it and re-run './bootstrap.sh bridge')"
      continue
    fi
    ip="$(docker inspect -f "{{(index .NetworkSettings.Networks \"$network\").IPAddress}}" "$cid")"
    if [[ -z "$ip" ]]; then
      echo "WARN: '$svc' has no IP on $network — skipping"
      continue
    fi
    cat <<EOF | k apply -f - >/dev/null
apiVersion: v1
kind: Service
metadata:
  name: $svc
  namespace: $namespace
spec:
  ports:
    - port: $port
      targetPort: $port
---
apiVersion: v1
kind: Endpoints
metadata:
  name: $svc
  namespace: $namespace
subsets:
  - addresses:
      - ip: $ip
    ports:
      - port: $port
EOF
    echo "  $svc → $ip:$port"
  done
}

ensureDatabase() {
  echo "── D-3: ensuring jmetercloud_k8srun exists + is migrated ──"
  pgc="$(composeCid postgres)"
  if [[ -z "$pgc" ]]; then
    echo "ERROR: compose 'postgres' is not running — 'docker compose up -d' first"; exit 1
  fi
  if ! docker exec "$pgc" psql -U "$pgUser" -d jmetercloud_metrics -tAc \
      "SELECT 1 FROM pg_database WHERE datname='jmetercloud_k8srun'" | grep -q 1; then
    docker exec "$pgc" psql -U "$pgUser" -d jmetercloud_metrics -c "CREATE DATABASE jmetercloud_k8srun"
    docker exec "$pgc" psql -U "$pgUser" -d jmetercloud_metrics -c \
      "GRANT CONNECT ON DATABASE jmetercloud_k8srun TO \"globalOrchestratorWriter\"; GRANT ALL PRIVILEGES ON DATABASE jmetercloud_k8srun TO $pgUser"
    echo "  created jmetercloud_k8srun"
  else
    echo "  jmetercloud_k8srun already exists"
  fi
  # Same image + flags as the postgres fragment's one-shot flyway-migrate job.
  docker run --rm --network "$network" \
    -v "$repoRoot/postgres/migrations/k8srun:/flyway/sql/k8srun:ro" \
    flyway/flyway:10 \
    -url=jdbc:postgresql://postgres:5432/jmetercloud_k8srun \
    -user="$pgUser" -password="$pgPassword" \
    -locations=filesystem:/flyway/sql/k8srun \
    migrate
}

case "${1:-up}" in
  bridge) bridge; exit 0 ;;
  down)   kind delete cluster --name "$cluster"; exit 0 ;;
  up)     ;;
  *)      echo "usage: $0 [up|bridge|down]"; exit 64 ;;
esac

echo "── F-1: kind cluster ──"
if ! kind get clusters 2>/dev/null | grep -qx "$cluster"; then
  kind create cluster --config "$scriptDir/kindConfig.yaml"
else
  echo "  cluster '$cluster' already exists"
fi
# Join the kind node to the compose network so bridge Endpoints are routable
# from pods (pod traffic NATs through the node). Idempotent.
docker network connect "$network" "$node" 2>/dev/null || echo "  node already on $network"

ensureDatabase

echo "── images ──"
if ! docker image inspect jmeter-local-orchestrator:dev >/dev/null 2>&1; then
  echo "ERROR: jmeter-local-orchestrator:dev not built — 'docker compose build' first"; exit 1
fi
# --provenance=false: BuildKit attestation manifests break `kind load`
# ("ctr: content digest not found").
docker build --provenance=false -t jmeter-orchestrator-k8s:dev "$projectDir"
kind load docker-image --name "$cluster" jmeter-orchestrator-k8s:dev jmeter-local-orchestrator:dev

echo "── manifests ──"
# The kustomize overlay carries namespace + rbac + workers + orchestrator;
# the orchestrator may crash-loop for a few seconds until the bridge
# Services (next step) exist — readiness converges.
k apply -k "$projectDir/kube/overlays/kind"
bridge
k -n "$namespace" rollout status deployment/jmeter-orchestrator-k8s --timeout=300s

cat <<EOF

Ready. Reach the orchestrator with:
  kubectl --context $kubectlCtx -n $namespace port-forward svc/jmeter-orchestrator-k8s 8088:8088
  curl -s localhost:8088/actuator/health

After a compose restart (container IPs change): ./kube/local/bootstrap.sh bridge
After rebuilding an image: docker build --provenance=false -t jmeter-orchestrator-k8s:dev . && kind load docker-image --name $cluster <image> && kubectl -n $namespace rollout restart deployment/jmeter-orchestrator-k8s
EOF
