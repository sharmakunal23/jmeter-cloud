#!/usr/bin/env bash
# Two local kind clusters that play two data centers, each running only a
# jmeter-regional-orchestrator; the hub stays on compose. Simulates the
# private-cloud shape: the global never holds a cluster credential and reaches
# each region at http://<cluster>-control-plane:30088 over the compose network.
#
#   ./bootstrapRegions.sh up [na-east na-west]   # clusters + images + regional + bridge
#   ./bootstrapRegions.sh bridge                  # re-sync bridge Services after `docker compose up`
#   ./bootstrapRegions.sh down                    # delete the clusters (compose untouched)
#
# Prereqs: kind + kubectl; the compose stack is up (global-orchestrator,
# metrics-consumer, document-service); jmeter-local-orchestrator:dev is built.
set -euo pipefail

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repoRoot="$(cd "$scriptDir/../../../.." && pwd)"
network="${COMPOSE_NETWORK:-jmeter-cloud_default}"
namespace="jmeter-cloud"
nodePort=30088
cmd="${1:-up}"; shift || true
regions=("$@"); [[ ${#regions[@]} -eq 0 ]] && regions=(na-east na-west)

# Hub services workers and the regional reach FROM INSIDE a cluster, mirrored
# as in-cluster Services + manual Endpoints under the compose names so the
# defaults (http://global-orchestrator:8082, …) work unchanged.
bridgeDeps=(global-orchestrator:8082 metrics-consumer:8083 document-service:8084)

k() { kubectl --context "kind-$1" "${@:2}"; }
composeCid() { (cd "$repoRoot" && docker compose ps -q "$1"); }

bridge() {
  local cluster="$1"
  echo "── $cluster: bridge Services (network=$network) ──"
  for dep in "${bridgeDeps[@]}"; do
    local svc="${dep%%:*}" port="${dep##*:}" cid ip
    cid="$(composeCid "$svc")"
    if [[ -z "$cid" ]]; then echo "WARN: compose service '$svc' is not running — skipped"; continue; fi
    ip="$(docker inspect -f "{{(index .NetworkSettings.Networks \"$network\").IPAddress}}" "$cid")"
    if [[ -z "$ip" ]]; then echo "WARN: '$svc' has no IP on $network — skipped"; continue; fi
    cat <<YAML | k "$cluster" apply -f - >/dev/null
apiVersion: v1
kind: Service
metadata: {name: $svc, namespace: $namespace}
spec:
  ports: [{port: $port, targetPort: $port}]
---
apiVersion: v1
kind: Endpoints
metadata: {name: $svc, namespace: $namespace}
subsets: [{addresses: [{ip: $ip}], ports: [{port: $port}]}]
YAML
    echo "  $svc → $ip:$port"
  done
}

regionsLine() {
  local out=()
  for c in "${regions[@]}"; do out+=("$c=http://$c-control-plane:$nodePort"); done
  local IFS=,; echo "REGIONS=${out[*]}"
}

case "$cmd" in
  bridge) for c in "${regions[@]}"; do bridge "$c"; done; exit 0 ;;
  down)   for c in "${regions[@]}"; do kind delete cluster --name "$c"; done; exit 0 ;;
  up)     ;;
  *)      echo "usage: $0 [up|bridge|down] [region ...]"; exit 64 ;;
esac

if ! docker image inspect jmeter-local-orchestrator:dev >/dev/null 2>&1; then
  echo "ERROR: jmeter-local-orchestrator:dev not built — 'docker compose build' first"; exit 1
fi
# --provenance=false: BuildKit attestation manifests break `kind load`.
docker build --provenance=false -t jmeter-regional-orchestrator:dev "$repoRoot/jmeter-regional-orchestrator"

for c in "${regions[@]}"; do
  echo "── $c: cluster ──"
  if ! kind get clusters 2>/dev/null | grep -qx "$c"; then
    kind create cluster --name "$c"
  else
    echo "  cluster '$c' already exists"
  fi
  # Join the node to the compose network: pods reach the bridge Endpoints
  # through it, and the hub reaches this node's NodePort by container name.
  docker network connect "$network" "$c-control-plane" 2>/dev/null || echo "  node already on $network"
  kind load docker-image --name "$c" jmeter-regional-orchestrator:dev jmeter-local-orchestrator:dev
  k "$c" apply -k "$repoRoot/jmeter-regional-orchestrator/kube/overlays/kind"
  # The overlay ships REGION=local; this cluster IS a region.
  k "$c" -n "$namespace" set env deployment/jmeter-regional-orchestrator REGION="$c" >/dev/null
  bridge "$c"
  k "$c" -n "$namespace" rollout status deployment/jmeter-regional-orchestrator --timeout=300s
done

cat <<EOT

Ready. Point the compose hub at the regions — in .env:
  PROVISIONING_MODE=DYNAMIC
  $(regionsLine)
then: docker compose up -d global-orchestrator
Check: curl -s localhost:8082/api/v1/regions/status | jq

After a compose restart (container IPs change): $0 bridge
Reach a regional directly: kubectl --context kind-${regions[0]} -n $namespace port-forward svc/jmeter-regional-orchestrator 8088:8088
EOT
