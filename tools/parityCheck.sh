#!/usr/bin/env bash
#
# parityCheck.sh — the orchestrator parity guard.
#
# `jmeter-global-orchestrator` and `jmeter-orchestrator-k8s` are two
# deployments of ONE control plane. Which one an operator talks to is a
# hosting decision (docker socket vs cluster API), not a capability decision,
# so the two MUST carry the same functionality: same REST surface, same
# domain behaviour, same config knobs, same Flyway versions, same tests.
#
# This script proves that mechanically. It normalises the handful of
# deltas that are supposed to differ, diffs everything, and fails on
# anything that is not on the allow-list below.
#
#   ./tools/parityCheck.sh            # summary; exit 1 on drift
#   ./tools/parityCheck.sh -v         # ...and print every offending diff
#   ./tools/parityCheck.sh --allowed  # print the allow-list and exit
#
# ── THE TRAP THIS SCRIPT ENCODES ─────────────────────────────────────────
# Four names say "global" in BOTH services on purpose (see SHARED_LITERALS
# below) — starting with the SQL schema, which is called "globalOrchestrator"
# inside both databases. So a blanket
#     sed s/globalOrchestrator/k8sOrchestrator/g
# when porting a change silently rewrites every SQL string and breaks the k8s
# service against its own database. It still compiles.
#
# Two defences here. The normaliser protects those literals FIRST, before any
# other rename, so a file that got the blanket sed shows up as drift. And
# checkSharedLiterals verifies each one still exists on both sides, which
# catches the case the file-by-file diff structurally cannot see.
#
# Use ./tools/portToTwin.sh to mirror a change — it encodes the safe renames.
# ─────────────────────────────────────────────────────────────────────────

set -uo pipefail

GLOBAL_DIR="jmeter-global-orchestrator"
K8S_DIR="jmeter-orchestrator-k8s"

# ── Allowed divergences ──────────────────────────────────────────────────
# The COMPLETE list. Anything that differs and is not here is drift, and a
# bug. Format: <section>:<normalised path>|<why it is legitimate>
#
# Adding an entry is a real decision — it permanently exempts a file from
# the parity rule. Prefer converging the two files instead.
ALLOWED=(
  "main:com/perf/@@PKG@@/provision/DockerSocketPodProvisioner.java|Docker substrate is global-only — the k8s twin has no docker socket. Both implement the same PodProvisioner interface, so this is a substrate detail, not a capability."
  "main:com/perf/@@PKG@@/provision/PodProvisioner.java|Interface javadoc names the substrate each service actually runs on."
  "main:com/perf/@@PKG@@/provision/ProvisionerConfig.java|Substrate-specific label prefix + managedBy value, and the docker-only knobs global also binds."
  "main:com/perf/@@PKG@@/provision/ProvisionerProperties.java|Substrate-specific defaults (dockerHost/network vs namespace/headlessService) and their javadoc."
  "main:com/perf/@@PKG@@/provision/K8sPodProvisioner.java|Global's copy is substrate-SELECTED (@ConditionalOnProperty on podProvisioner.substrate) and uses the docker label prefix for cross-substrate reconciler parity; the k8s twin's is unconditional."
  "main:com/perf/@@PKG@@/provision/PodReconciler.java|Global's wildcard orphan pass uses the docker label query; the twin walks known (applicationId, region) pairs because PodProvisioner.listFor requires a non-null applicationId. Same behaviour, different traversal."
  "test:com/perf/@@PKG@@/provision/K8sPodProvisionerTest.java|Mirrors the substrate divergence in the class under test."
  "migrations:README.md|k8srun/ carries a README explaining why the SQL schema name inside it is still \"globalOrchestrator\". globalrun/ needs no such note."
)

VERBOSE=0
case "${1:-}" in
  -v|--verbose) VERBOSE=1 ;;
  --allowed)
    printf 'Allowed divergences (%d):\n\n' "${#ALLOWED[@]}"
    for entry in "${ALLOWED[@]}"; do
      printf '  %s\n      %s\n\n' "${entry%%|*}" "${entry#*|}"
    done
    exit 0 ;;
  -h|--help) sed -n '3,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  "") ;;
  *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
esac

cd "$(dirname "$0")/.." || exit 2
[ -d "$GLOBAL_DIR" ] && [ -d "$K8S_DIR" ] || {
  echo "run this from the repo (missing $GLOBAL_DIR or $K8S_DIR)" >&2; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ── The normaliser ───────────────────────────────────────────────────────
# Collapses every delta that is SUPPOSED to differ down to a shared token,
# so whatever is left over is real. Order matters: the two SQL literals are
# protected first, and each longer name is rewritten before the shorter one
# it contains.
#
# Lines between a `parity:ignore-start` and a `parity:ignore-end` marker (in
# whatever comment syntax the file uses) are dropped. That is the preferred
# way to record a divergence: it sits at the point of divergence where the
# next reader will see it, instead of in a whole-file exemption far away.
# Use it for a substrate-specific config key or a single-service block —
# never to paper over behaviour that ought to be mirrored.
normalise() {
  sed '/parity:ignore-start/,/parity:ignore-end/d' \
  | sed -e 's|\\"globalOrchestrator\\"|@@SQLSCHEMA@@|g' \
      -e 's|"globalOrchestrator"|@@SQLSCHEMA@@|g' \
      -e 's|globalOrchestratorWriter|@@SQLROLE@@|g' \
      -e 's|com\.perf\.globalorchestrator|com.perf.@@PKG@@|g' \
      -e 's|com\.perf\.k8sorchestrator|com.perf.@@PKG@@|g' \
      -e 's|globalorchestrator|@@PKG@@|g' \
      -e 's|k8sorchestrator|@@PKG@@|g' \
      -e 's|GlobalOrchestratorApplication|@@APP@@|g' \
      -e 's|K8sOrchestratorApplication|@@APP@@|g' \
      -e 's|globalOrchestrator|@@PREFIX@@|g' \
      -e 's|k8sOrchestrator|@@PREFIX@@|g' \
      -e 's|POSTGRES_GLOBALRUN_|POSTGRES_@@RUNDB@@_|g' \
      -e 's|POSTGRES_K8SRUN_|POSTGRES_@@RUNDB@@_|g' \
      -e 's|GLOBAL_ORCHESTRATOR_|@@ENVPREFIX@@_|g' \
      -e 's|K8S_ORCHESTRATOR_|@@ENVPREFIX@@_|g' \
      -e 's|jmetercloud_globalrun|jmetercloud_@@RUNDB@@|g' \
      -e 's|jmetercloud_k8srun|jmetercloud_@@RUNDB@@|g' \
      -e 's|globalrun|@@RUNDB@@|g' \
      -e 's|k8srun|@@RUNDB@@|g' \
      -e 's|jmeter-global-orchestrator|@@SVC@@|g' \
      -e 's|jmeter-orchestrator-k8s|@@SVC@@|g' \
      -e 's|global-orchestrator|@@SVC@@|g' \
      -e 's|k8s-orchestrator|@@SVC@@|g' \
      -e 's|8082|@@PORT@@|g' \
      -e 's|8088|@@PORT@@|g'
}

isAllowed() {  # <section> <normalised path>
  local key="$1:$2"
  for entry in "${ALLOWED[@]}"; do
    [ "${entry%%|*}" = "$key" ] && return 0
  done
  return 1
}

DRIFT=0
MISSING=0
DIFFERING=0
EXEMPT=0

# ── One section: two directories that must hold the same files, with the
#    same contents once normalised. ───────────────────────────────────────
compareSection() {  # <section> <globalRoot> <k8sRoot> <find-args...>
  local section="$1" gRoot="$2" kRoot="$3"; shift 3

  if [ ! -d "$gRoot" ] || [ ! -d "$kRoot" ]; then
    printf '  %-11s SKIP (directory absent)\n' "$section"
    return
  fi

  ( cd "$gRoot" && find . "$@" -type f 2>/dev/null ) | normalise | sed 's|^\./||' | sort > "$WORK/$section.g"
  ( cd "$kRoot" && find . "$@" -type f 2>/dev/null ) | normalise | sed 's|^\./||' | sort > "$WORK/$section.k"

  local missing=0 differing=0 exempt=0 checked=0

  # Files present on one side only.
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    if isAllowed "$section" "$p"; then
      exempt=$((exempt + 1))
    else
      missing=$((missing + 1))
      echo "  MISSING in $K8S_DIR: $section/$p" >> "$WORK/report"
    fi
  done < <(comm -23 "$WORK/$section.g" "$WORK/$section.k")

  while IFS= read -r p; do
    [ -z "$p" ] && continue
    if isAllowed "$section" "$p"; then
      exempt=$((exempt + 1))
    else
      missing=$((missing + 1))
      echo "  MISSING in $GLOBAL_DIR: $section/$p" >> "$WORK/report"
    fi
  done < <(comm -13 "$WORK/$section.g" "$WORK/$section.k")

  # Files on both sides — contents must match once normalised.
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    local gFile kFile
    gFile="$(denormaliseGlobal "$gRoot" "$p")"
    kFile="$(denormaliseK8s "$kRoot" "$p")"
    [ -f "$gFile" ] && [ -f "$kFile" ] || continue
    checked=$((checked + 1))
    if ! diff -q <(normalise < "$gFile") <(normalise < "$kFile") > /dev/null 2>&1; then
      if isAllowed "$section" "$p"; then
        exempt=$((exempt + 1))
      else
        differing=$((differing + 1))
        echo "  DIFFERS: $section/$p" >> "$WORK/report"
        if [ "$VERBOSE" = 1 ]; then
          {
            echo "      --- $gFile"
            echo "      +++ $kFile"
            diff <(normalise < "$gFile") <(normalise < "$kFile") | sed 's/^/      /'
            echo
          } >> "$WORK/report"
        fi
      fi
    fi
  done < <(comm -12 "$WORK/$section.g" "$WORK/$section.k")

  MISSING=$((MISSING + missing))
  DIFFERING=$((DIFFERING + differing))
  EXEMPT=$((EXEMPT + exempt))

  local status="ok"
  [ $((missing + differing)) -gt 0 ] && status="DRIFT"
  printf '  %-11s %-6s %3d compared, %d missing, %d differing, %d allowed\n' \
    "$section" "$status" "$checked" "$missing" "$differing" "$exempt"
}

# A normalised path has to be turned back into a real one to read the file.
# Only the package segment and the entry-point class name are ambiguous.
denormaliseGlobal() {  # <root> <normalised path>
  echo "$1/$(echo "$2" | sed -e 's|@@PKG@@|globalorchestrator|g' -e 's|@@APP@@|GlobalOrchestratorApplication|g')"
}
denormaliseK8s() {
  echo "$1/$(echo "$2" | sed -e 's|@@PKG@@|k8sorchestrator|g' -e 's|@@APP@@|K8sOrchestratorApplication|g')"
}

# ── Shared literals ──────────────────────────────────────────────────────
# Names that say "global" in BOTH services on purpose. The normaliser
# collapses the global/k8s spellings together, so a file-by-file diff can
# never see one of these being renamed — this canary can. Renaming any of
# them still compiles; it fails at runtime, against the twin's own database
# or its own workers.
SHARED_LITERALS=(
  '"globalOrchestrator"|the SQL schema name inside BOTH run databases'
  'globalOrchestratorWriter|the writer role in BOTH run databases'
  'globalOrchestratorUrl|provisioner key: the URL workers call home on'
  'GLOBAL_ORCHESTRATOR_URL|its env var; the worker contract names it that in both'
)

checkSharedLiterals() {
  local broken=0
  for entry in "${SHARED_LITERALS[@]}"; do
    local lit="${entry%%|*}" why="${entry#*|}" gHits kHits
    gHits=$(grep -rF --include='*.java' --include='*.yml' --include='*.yaml' \
              -l -- "$lit" "$GLOBAL_DIR/src" "$GLOBAL_DIR/api" 2>/dev/null | wc -l | tr -d ' ')
    kHits=$(grep -rF --include='*.java' --include='*.yml' --include='*.yaml' \
              -l -- "$lit" "$K8S_DIR/src" "$K8S_DIR/api" 2>/dev/null | wc -l | tr -d ' ')
    if [ "$gHits" -gt 0 ] && [ "$kHits" -eq 0 ]; then
      broken=$((broken + 1))
      echo "  RENAMED IN TWIN: $lit — $why" >> "$WORK/report"
    fi
  done
  if [ "$broken" -gt 0 ]; then
    MISSING=$((MISSING + broken))
    printf '  %-11s %-6s %d shared literal(s) lost in the twin\n' "literals" "DRIFT" "$broken"
  else
    printf '  %-11s %-6s %3d checked, both sides carry every one\n' \
      "literals" "ok" "${#SHARED_LITERALS[@]}"
  fi
}

: > "$WORK/report"

echo "Orchestrator parity check"
echo "  $GLOBAL_DIR  vs  $K8S_DIR"
echo

compareSection main       "$GLOBAL_DIR/src/main/java"      "$K8S_DIR/src/main/java"      -name '*.java'
compareSection test       "$GLOBAL_DIR/src/test/java"      "$K8S_DIR/src/test/java"      -name '*.java'
compareSection mainRes    "$GLOBAL_DIR/src/main/resources" "$K8S_DIR/src/main/resources"
compareSection testRes    "$GLOBAL_DIR/src/test/resources" "$K8S_DIR/src/test/resources"
compareSection api        "$GLOBAL_DIR/api"                "$K8S_DIR/api"
compareSection migrations "postgres/migrations/globalrun"  "postgres/migrations/k8srun"
checkSharedLiterals

echo
if [ $((MISSING + DIFFERING)) -gt 0 ]; then
  DRIFT=1
  echo "DRIFT — $MISSING file(s) present on one side only, $DIFFERING differing:"
  echo
  cat "$WORK/report"
  echo
  echo "A change is not done until it exists in BOTH orchestrators."
  echo "Port the diff (not the file) applying only these renames:"
  echo "    package  com.perf.globalorchestrator  <->  com.perf.k8sorchestrator"
  echo "    property \${globalOrchestrator.        <->  \${k8sOrchestrator."
  echo "    env      POSTGRES_GLOBALRUN_*         <->  POSTGRES_K8SRUN_*"
  echo "    env      GLOBAL_ORCHESTRATOR_*        <->  K8S_ORCHESTRATOR_*"
  echo "    db       jmetercloud_globalrun        <->  jmetercloud_k8srun"
  echo "    port     8082                         <->  8088"
  echo
  echo "NEVER rename the SQL schema literal \"globalOrchestrator\" or the role"
  echo "globalOrchestratorWriter — both databases share those names on purpose."
  [ "$VERBOSE" = 0 ] && echo && echo "Re-run with -v to see the diffs."
else
  echo "PARITY OK — the two orchestrators are identical modulo ${EXEMPT} allowed divergence(s)."
  echo "            (./tools/parityCheck.sh --allowed lists them and why)"
fi

exit $DRIFT
