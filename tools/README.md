# tools/

Repo-level scripts that belong to no single subsystem. There is deliberately
no top-level build here — each subsystem still builds alone. These are
standalone shell scripts; run them from the repository root.

## The orchestrator parity guard

`jmeter-global-orchestrator` and `jmeter-orchestrator-k8s` are two deployments
of **one** control plane. Which one an operator talks to is a *hosting*
decision (docker socket vs cluster API), not a capability decision, so the two
must carry the same functionality: same REST surface, same domain behaviour,
same config knobs, same Flyway versions, same tests.

That rule used to be enforced by discipline alone, which is exactly how a whole
feature once shipped into one orchestrator and not the other. These two scripts
make it mechanical.

| Script | What it does |
|---|---|
| `parityCheck.sh` | The gate. Normalises the deltas that are *supposed* to differ, diffs both source trees, resources, `api/openapi.yaml` and the two migration directories, and exits `1` on anything not on its allow-list. |
| `portToTwin.sh` | Mirrors one file into the other service, applying only the safe renames. |

```sh
./tools/parityCheck.sh            # summary; exit 1 on drift
./tools/parityCheck.sh -v         # ...and print every offending diff
./tools/parityCheck.sh --allowed  # what may differ, and why

./tools/portToTwin.sh <file>            # print the translation
./tools/portToTwin.sh <file> --diff     # compare against the twin's copy
./tools/portToTwin.sh <file> --write    # write it to the twin's path
```

### Why `portToTwin.sh` exists instead of a `sed` you type yourself

Four names say "global" in **both** services on purpose:

| Name | What it is |
|---|---|
| `"globalOrchestrator"` | the SQL schema — same name inside *both* run databases |
| `globalOrchestratorWriter` | the writer role, likewise |
| `globalOrchestratorUrl` | provisioner key: the URL workers call home on |
| `GLOBAL_ORCHESTRATOR_URL` | its env var — the *worker's* contract names it that whichever orchestrator answers |

So the obvious `sed s/globalOrchestrator/k8sOrchestrator/g` rewrites every SQL
string and the worker's call-home address. It still compiles. It fails at
runtime, against the twin's own database. `portToTwin.sh` protects those four
before renaming anything else, and `parityCheck.sh` carries a canary that
verifies each one still exists on both sides — a check the file-by-file diff
structurally cannot make, because the normaliser collapses both spellings
together.

### Recording a divergence

Two ways, in order of preference:

1. **`parity:ignore-start` / `parity:ignore-end` markers** in the file itself,
   in whatever comment syntax it uses. The normaliser drops everything between
   them. This is preferred because the exemption sits where the divergence is,
   in front of the next person to read that code. Use it for a
   substrate-specific config key or a single-service block.
2. **The `ALLOWED` list at the top of `parityCheck.sh`**, for a file that
   diverges wholesale (the docker provisioner, for instance). Each entry
   carries the reason. Adding one is a real decision — it exempts that file
   permanently, so prefer converging the two files instead.

Anything that differs and is in neither place is drift, and a bug.
