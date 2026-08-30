# oracle/groups — application-group descriptors

One JSON descriptor per application group; `renderGroup.mjs` turns it into
the group's SQL bundle `../migrations/metrics/R__group_<groupId>.sql` — the
hosted environment's per-group steps with every object named from
`UPPER(groupId)` (`cps` → `CPS_METRICS`, `CPS_METRICS_H`, `CPS_CLASSIFY_LABEL`,
`CPS_ARCHIVE_TO_H`, `CPS_PRUNE_H`, `CPS_MAINTAIN`, job `CPS_NIGHTLY_MAINT`).
The rendered file is committed: locally Flyway applies it as a repeatable
migration, on the hosted database the DBA runs the same file.

```bash
node oracle/groups/renderGroup.mjs --all           # (re)render every descriptor
node oracle/groups/renderGroup.mjs --check --all   # fail if a rendered file is stale
docker compose up flyway-migrate                   # apply locally (repeatables re-run when changed)
```

| Field | Meaning |
|---|---|
| `groupId` | `[a-z][a-z0-9_]{0,29}` — what workers send as `?groupId=`; also the control plane's `applicationGroup.groupId` |
| `name` | display name → `GROUP_REGISTRY.GROUP_NAME` |
| `applications[]` | `name` (the `LABEL.APPLICATION` value, e.g. `CPS-PCI`) + `labelPrefixes[]`; first matching prefix wins, in list order |
| `unclassified` | the application a label with no matching prefix gets (default `OTHER`) |
| `hotDays` / `historyDays` | days a day's partition stays in `<P>_METRICS` before it is collapsed into `<P>_METRICS_H` / days it stays there |
| `readers[]` / `purgers[]` | database users granted `SELECT` / `SELECT, DELETE` on the group's tables (Grafana users go in `readers`) |
| `maintenance` | `timeZone`, `fromHour`, `toHour` — the nightly job picks a random minute in that window at deploy time |

Onboarding a group is three commands: write the descriptor, render, apply —
then register the same `groupId` as an application group in the UI.

## Grafana dashboards (`--grafana`)

`node oracle/groups/renderGroup.mjs --grafana --all` also renders the two hosted
dashboards per group into `oracle/groups/grafana/<groupId>Live.json` (over
`<P>_METRICS`, uid `<groupId>ProductMetrics`) and `<groupId>History.json`
(over `<P>_METRICS_H`, uid `<groupId>ProductMetricsHistory`) from
`grafana/templates/` — title, tables, prefix literal and the `application`
variable's options come from the descriptor; the Oracle datasource is an
import input (`${DS_ORACLE}`), so Grafana's import dialog asks for it.
`--check --grafana` fails on drift. Paste each dashboard's URL into the group
(`grafanaLiveUrl` / `grafanaHistoryUrl`, "Manage groups" in the UI) and the run
page's "Open in Grafana" opens it on the run's time range.
