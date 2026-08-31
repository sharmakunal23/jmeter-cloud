#!/usr/bin/env bash
# Two local kind clusters that play two data centers, each running only a
# jmeter-regional-orchestrator; the hub stays on compose. Simulates the
# private-cloud shape: the global never holds a cluster credential and reaches
# each region at http://<cluster>-control-plane:30088 over the compose network.
#
#   ./bootstrapRegions.sh up [na-east na-west]   # clusters + images + regional + bridge + registration
#   ./bootstrapRegions.sh bridge                  # re-sync bridge Services after `docker compose up`
#   ./bootstrapRegions.sh down                    # delete + deregister the clusters (compose untouched)
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

# CLUSTER-CAPACITY — the cluster registry lives in the hub's database, not an
# env var: register each kind cluster through the validated API. A 409
# CLUSTER_EXISTS is fine (idempotent re-run); a 422 prints the hub's checks.
hub="${HUB_URL:-http://localhost:8082}"
registerCluster() {
  local c="$1" body status
  body="$(printf '{"region":"%s","label":"%s (kind)","regionalUrl":"http://%s-control-plane:%s","maxWorkers":20}' \
          "$c" "$c" "$c" "$nodePort")"
  # `|| true` so an unreachable hub cannot kill the script under `set -e` AFTER
  # the clusters, images and regionals are already up — report and carry on.
  status="$(curl -s -o /tmp/registerCluster.$$ -w '%{http_code}' -X POST "$hub/api/v1/regions" \
            -H 'Content-Type: application/json' -d "$body" || echo 000)"
  case "$status" in
    201) echo "  cluster $c registered (validated)" ;;
    409) echo "  cluster $c already registered" ;;
    000) echo "WARN: hub unreachable at $hub — cluster $c is NOT registered."
         echo "      Start the hub, then: $0 up $c   (or POST /api/v1/regions yourself)" ;;
    *)   echo "WARN: cluster $c registration → HTTP $status:"; cat /tmp/registerCluster.$$; echo ;;
  esac
  rm -f /tmp/registerCluster.$$
}

# A cluster that still holds reservations or workers is refused by design —
# say so rather than swallowing the 409 and leaving a dead cluster listed.
deregisterCluster() {
  local c="$1" status
  status="$(curl -s -o /tmp/deregisterCluster.$$ -w '%{http_code}' \
            -X DELETE "${HUB_URL:-http://localhost:8082}/api/v1/regions/$c" || echo 000)"
  case "$status" in
    204) echo "  cluster $c deregistered" ;;
    404) echo "  cluster $c was not registered" ;;
    409) echo "  cluster $c KEPT — it still holds reservations or workers:"
         cat /tmp/deregisterCluster.$$; echo
         echo "      release them (Capacity page) then: curl -X DELETE $hub/api/v1/regions/$c" ;;
    000) echo "  hub unreachable — cluster $c left registered" ;;
    *)   echo "  cluster $c deregistration → HTTP $status"; cat /tmp/deregisterCluster.$$; echo ;;
  esac
  rm -f /tmp/deregisterCluster.$$
}

case "$cmd" in
  bridge) for c in "${regions[@]}"; do bridge "$c"; done; exit 0 ;;
  down)   for c in "${regions[@]}"; do
            deregisterCluster "$c"      # BEFORE the kind delete: a cluster with
            kind delete cluster --name "$c"   # reservations/workers is refused, and
          done; exit 0 ;;                     # the operator must be told, not silenced
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
  k "$c" apply -k "$repoRoot/jmeter-regional-orchestrator/kube/kustomize/overlays/local"
  # The overlay ships REGION=local; this cluster IS a region.
  k "$c" -n "$namespace" set env deployment/jmeter-regional-orchestrator REGION="$c" >/dev/null
  bridge "$c"
  k "$c" -n "$namespace" rollout status deployment/jmeter-regional-orchestrator --timeout=300s
done

echo "── registering the clusters with the hub ($hub) ──"
for c in "${regions[@]}"; do registerCluster "$c"; done

cat <<EOT

Ready. The clusters are registered in the hub's ORCH_REGION registry
(PROVISIONING_MODE and REGIONS are retired — CLUSTER-CAPACITY).
Check: curl -s localhost:8082/api/v1/regions/status | jq

After a compose restart (container IPs change): $0 bridge
Reach a regional directly: kubectl --context kind-${regions[0]} -n $namespace port-forward svc/jmeter-regional-orchestrator 8088:8088
EOT
