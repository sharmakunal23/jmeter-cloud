#!/usr/bin/env bash
# buildAll.sh — exercises every documented Maven profile combination,
# prints the shaded fat-JAR size for each, and exits non-zero if any
# build exceeds its size budget. Intended as a CI gate run after
# regular tests pass.
#
# Budgets (must agree with the service's documented "Hard constraints"):
#
#   default                                ≤ 60 MB
#   -Pstorage-docservice                   ≤ 60 MB  (JDK HttpClient — no growth)
#   -Pstorage-s3                           ≤ 75 MB  (AWS SDK delta)
#   -Pstorage-s3,storage-docservice        ≤ 75 MB  (transitives overlap)
#
# Usage:
#   scripts/buildAll.sh [-q]
#     -q   quiet (suppress mvn output, only print summary)

set -euo pipefail

QUIET=""
if [[ "${1:-}" == "-q" ]]; then
  QUIET="-q"
  shift || true
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

JAR_GLOB="target/jmeter-local-orchestrator-*.jar"

# Each row: <label>|<profile-args>|<budget-mb>
COMBOS=(
  "default                       |                                       |60"
  "storage-docservice            |-Pstorage-docservice                   |60"
  "storage-s3                    |-Pstorage-s3                           |75"
  "storage-s3+docservice         |-Pstorage-s3,storage-docservice        |75"
)

failed=0
results=()

shaded_jar() {
  # Picks the non-original-* shaded JAR, sorted by mtime, last wins.
  /bin/ls -1t $JAR_GLOB 2>/dev/null | grep -v '/original-' | head -n 1
}

mb() {
  awk "BEGIN { printf \"%.1f\", $1 / 1024 / 1024 }"
}

for combo in "${COMBOS[@]}"; do
  IFS='|' read -r label profiles budget <<< "$combo"
  label=$(echo "$label" | xargs)
  profiles=$(echo "$profiles" | xargs)
  budget=$(echo "$budget" | xargs)

  echo
  echo "==> mvn -DskipTests $QUIET clean package $profiles"
  if ! mvn -DskipTests $QUIET clean package $profiles >/dev/null; then
    results+=("FAIL  $label  build failed")
    failed=$((failed + 1))
    continue
  fi

  jar=$(shaded_jar)
  if [[ -z "$jar" ]]; then
    results+=("FAIL  $label  shaded JAR not produced")
    failed=$((failed + 1))
    continue
  fi

  size_bytes=$(stat -f "%z" "$jar" 2>/dev/null || stat -c "%s" "$jar")
  size_mb=$(mb "$size_bytes")
  budget_bytes=$((budget * 1024 * 1024))

  if (( size_bytes > budget_bytes )); then
    results+=("FAIL  $label  ${size_mb} MB  >  ${budget} MB budget")
    failed=$((failed + 1))
  else
    results+=("OK    $label  ${size_mb} MB  <=  ${budget} MB budget")
  fi
done

echo
echo "================================================================"
echo "build-all summary"
echo "================================================================"
for r in "${results[@]}"; do
  echo "$r"
done
echo

if (( failed > 0 )); then
  echo "$failed combo(s) failed"
  exit 1
fi
echo "all combos within budget"
