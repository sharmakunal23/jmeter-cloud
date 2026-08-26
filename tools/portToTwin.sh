#!/usr/bin/env bash
#
# portToTwin.sh — translate a file between the two orchestrators, safely.
#
# `jmeter-global-orchestrator` and `jmeter-orchestrator-k8s` must carry the
# same functionality, so every change to one gets mirrored into the other.
# The mirror is a handful of mechanical renames — and one of them is a
# landmine, which is why this script exists instead of a hand-rolled sed:
#
#   The SQL schema inside BOTH databases is named "globalOrchestrator" and
#   the writer role in both is globalOrchestratorWriter. A blanket
#   sed s/globalOrchestrator/k8sOrchestrator/g rewrites every SQL string
#   and breaks the twin against its own database — silently, because it
#   still compiles.
#
# This script protects those two literals before renaming anything else.
#
#   ./tools/portToTwin.sh <file>            # print the translation
#   ./tools/portToTwin.sh <file> --write    # write it to the twin's path
#   ./tools/portToTwin.sh <file> --diff     # diff against what's there now
#
# Direction is inferred from the path. Translating a NEW file writes the
# twin's copy; translating an existing one overwrites it, so run --diff
# first when the twin has local edits worth keeping.
#
# Verify with ./tools/parityCheck.sh afterwards — that is the gate.

set -uo pipefail

FILE="${1:-}"
MODE="${2:-print}"
[ -n "$FILE" ] || { sed -n '3,26p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
[ -f "$FILE" ] || { echo "no such file: $FILE" >&2; exit 2; }

case "$FILE" in
  jmeter-global-orchestrator/*|./jmeter-global-orchestrator/*) DIR=toK8s ;;
  jmeter-orchestrator-k8s/*|./jmeter-orchestrator-k8s/*)       DIR=toGlobal ;;
  postgres/migrations/globalrun/*)                             DIR=toK8s ;;
  postgres/migrations/k8srun/*)                                DIR=toGlobal ;;
  *) echo "not an orchestrator file (or a run-db migration): $FILE" >&2; exit 2 ;;
esac

# Protect the shared identifiers, rename, then restore them.
#
# These four names say "global" in BOTH services on purpose and must survive
# the port untouched:
#   "globalOrchestrator"     — the SQL schema, same name inside both databases
#   globalOrchestratorWriter — the writer role, likewise
#   globalOrchestratorUrl    — provisioner key: the URL workers call home on
#   GLOBAL_ORCHESTRATOR_URL  — its env var; the WORKER's contract names it
#                              that whichever orchestrator answers
# Renaming any of them compiles fine and fails at runtime, which is the whole
# reason this script exists.
protect() {
  sed -e 's|\\"globalOrchestrator\\"|@@KEEP_SCHEMA_ESC@@|g' \
      -e 's|"globalOrchestrator"|@@KEEP_SCHEMA@@|g' \
      -e 's|globalOrchestratorWriter|@@KEEP_ROLE@@|g' \
      -e 's|globalOrchestratorUrl|@@KEEP_URL_KEY@@|g' \
      -e 's|GLOBAL_ORCHESTRATOR_URL|@@KEEP_URL_ENV@@|g'
}
restore() {
  sed -e 's|@@KEEP_SCHEMA_ESC@@|\\"globalOrchestrator\\"|g' \
      -e 's|@@KEEP_SCHEMA@@|"globalOrchestrator"|g' \
      -e 's|@@KEEP_ROLE@@|globalOrchestratorWriter|g' \
      -e 's|@@KEEP_URL_KEY@@|globalOrchestratorUrl|g' \
      -e 's|@@KEEP_URL_ENV@@|GLOBAL_ORCHESTRATOR_URL|g'
}

toK8s() {
  sed -e 's|jmeter-global-orchestrator|jmeter-orchestrator-k8s|g' \
      -e 's|global-orchestrator|k8s-orchestrator|g' \
      -e 's|com\.perf\.globalorchestrator|com.perf.k8sorchestrator|g' \
      -e 's|globalorchestrator|k8sorchestrator|g' \
      -e 's|GlobalOrchestratorApplication|K8sOrchestratorApplication|g' \
      -e 's|globalOrchestrator|k8sOrchestrator|g' \
      -e 's|POSTGRES_GLOBALRUN_|POSTGRES_K8SRUN_|g' \
      -e 's|GLOBAL_ORCHESTRATOR_|K8S_ORCHESTRATOR_|g' \
      -e 's|jmetercloud_globalrun|jmetercloud_k8srun|g' \
      -e 's|globalrun|k8srun|g' \
      -e 's|8082|8088|g'
}
toGlobal() {
  sed -e 's|jmeter-orchestrator-k8s|jmeter-global-orchestrator|g' \
      -e 's|k8s-orchestrator|global-orchestrator|g' \
      -e 's|com\.perf\.k8sorchestrator|com.perf.globalorchestrator|g' \
      -e 's|k8sorchestrator|globalorchestrator|g' \
      -e 's|K8sOrchestratorApplication|GlobalOrchestratorApplication|g' \
      -e 's|k8sOrchestrator|globalOrchestrator|g' \
      -e 's|POSTGRES_K8SRUN_|POSTGRES_GLOBALRUN_|g' \
      -e 's|K8S_ORCHESTRATOR_|GLOBAL_ORCHESTRATOR_|g' \
      -e 's|jmetercloud_k8srun|jmetercloud_globalrun|g' \
      -e 's|k8srun|globalrun|g' \
      -e 's|8088|8082|g'
}

if [ "$DIR" = toK8s ]; then
  TARGET="$(echo "$FILE" | toK8s)"
  BODY="$(protect < "$FILE" | toK8s | restore)"
else
  TARGET="$(echo "$FILE" | toGlobal)"
  BODY="$(protect < "$FILE" | toGlobal | restore)"
fi

case "$MODE" in
  print) printf '%s\n' "$BODY" ;;
  diff)
    if [ -f "$TARGET" ]; then
      diff -u "$TARGET" <(printf '%s\n' "$BODY") && echo "(twin already matches: $TARGET)"
    else
      echo "twin does not exist yet: $TARGET"
    fi ;;
  --write|write|-w)
    mkdir -p "$(dirname "$TARGET")"
    printf '%s\n' "$BODY" > "$TARGET"
    echo "wrote $TARGET" ;;
  --diff|-d) exec "$0" "$FILE" diff ;;
  *) echo "unknown mode: $MODE (print | --diff | --write)" >&2; exit 2 ;;
esac
