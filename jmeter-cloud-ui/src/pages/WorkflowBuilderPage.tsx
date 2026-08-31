import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import type { Connection } from "@xyflow/react";

import { applicationsApi, type Application } from "../api/applications";
import { applicationGroupsApi, type ApplicationGroup } from "../api/applicationGroups";
import { templatesApi, type TemplateSummary } from "../api/templates";
import {
  validationOf, workflowsApi,
  type EdgeCondition, type NodeType, type Workflow, type WorkflowGraph, type WorkflowValidation,
} from "../api/workflows";
import {
  EDGE_LABELS, NODE_LABELS, autoArrange, needsArrange, newNode, nextNodeId,
} from "../lib/workflowGraph";
import { InfoTip } from "../components/InfoTip";
import { WorkflowCanvas } from "../components/workflow/WorkflowCanvas";
import { NodeEditor } from "../components/workflow/NodeEditor";
import { CapacityPanel } from "../components/workflow/CapacityPanel";

const PALETTE: NodeType[] = ["HEALTH_CHECK", "LOAD_TEST", "EMAIL", "DELAY", "APPROVAL"];
const VALIDATE_DEBOUNCE_MS = 400;

const EMPTY_GRAPH: WorkflowGraph = { v: 1, nodes: [], edges: [] };

/**
 * The canvas. Adding a task, wiring two together and editing either is all one
 * surface, and the graph is validated server-side as the operator draws — the
 * capacity number beside the canvas is the same one the launch gate uses, so a
 * workflow that shows "fits" will start.
 */
export function WorkflowBuilderPage() {
  const { workflowId, groupId: groupIdParam } = useParams();
  const navigate = useNavigate();
  const editing = Boolean(workflowId);

  const [groupId, setGroupId] = useState(groupIdParam ?? "");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [enabled, setEnabled] = useState(true);
  const [revision, setRevision] = useState<number | null>(null);
  const [graph, setGraph] = useState<WorkflowGraph>(EMPTY_GRAPH);

  const [group, setGroup] = useState<ApplicationGroup | null>(null);
  const [applications, setApplications] = useState<Application[]>([]);
  const [templates, setTemplates] = useState<TemplateSummary[]>([]);

  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const [validation, setValidation] = useState<WorkflowValidation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  // ── Load ─────────────────────────────────────────────────────────
  useEffect(() => {
    const ac = new AbortController();
    (async () => {
      try {
        const [apps, tpls, groups] = await Promise.all([
          applicationsApi.list(ac.signal),
          templatesApi.list(ac.signal).catch(() => []),
          applicationGroupsApi.list(ac.signal),
        ]);
        let resolvedGroupId = groupIdParam ?? "";
        if (workflowId) {
          const wf = await workflowsApi.get(workflowId, ac.signal);
          resolvedGroupId = wf.groupId;
          setName(wf.name);
          setDescription(wf.description ?? "");
          setEnabled(wf.enabled);
          setRevision(wf.revision);
          setGraph(needsArrange(wf.graph) ? autoArrange(wf.graph) : wf.graph);
        }
        setGroupId(resolvedGroupId);
        setGroup(groups.find((g) => g.groupId === resolvedGroupId) ?? null);
        // A task may only name an application from its own group — the pool it
        // spends is the group's, so anything else would draw on capacity
        // nobody reserved.
        setApplications(apps.filter((a) => a.metricsGroupId === resolvedGroupId));
        setTemplates(tpls);
      } catch (e) {
        if ((e as Error)?.name === "AbortError") return;
        setError((e as Error).message);
      }
    })();
    return () => ac.abort();
  }, [workflowId, groupIdParam]);

  // ── Live validation ──────────────────────────────────────────────
  const validateTimer = useRef<number | undefined>(undefined);
  useEffect(() => {
    if (!groupId) return;
    window.clearTimeout(validateTimer.current);
    validateTimer.current = window.setTimeout(() => {
      workflowsApi.validate(groupId, graph)
        .then(setValidation)
        .catch(() => setValidation(null));
    }, VALIDATE_DEBOUNCE_MS);
    return () => window.clearTimeout(validateTimer.current);
  }, [groupId, graph]);

  // ── Graph edits ──────────────────────────────────────────────────
  const mutate = useCallback((next: WorkflowGraph) => {
    setGraph(next);
    setDirty(true);
  }, []);

  function addNode(type: NodeType) {
    const ids = new Set(graph.nodes.map((n) => n.id));
    const id = nextNodeId(type, ids);
    // Drop it under the current bottom row so it never lands on top of another.
    const bottom = graph.nodes.reduce((y, n) => Math.max(y, n.position?.y ?? 0), 0);
    const node = newNode(type, id, { x: 60 + (graph.nodes.length % 3) * 260, y: bottom + 150 });
    mutate({ ...graph, nodes: [...graph.nodes, node] });
    setSelectedNodeId(id);
    setSelectedEdgeId(null);
  }

  function connect(connection: Connection) {
    if (!connection.source || !connection.target) return;
    if (connection.source === connection.target) return;
    const id = `${connection.source}-${connection.target}-ON_SUCCESS`;
    if (graph.edges.some((e) => e.id === id)) return;
    mutate({
      ...graph,
      edges: [...graph.edges, {
        id, source: connection.source, target: connection.target, condition: "ON_SUCCESS",
      }],
    });
    setSelectedEdgeId(id);
    setSelectedNodeId(null);
  }

  function deleteSelection(nodeIds: string[], edgeIds: string[]) {
    if (nodeIds.length === 0 && edgeIds.length === 0) return;
    const gone = new Set(nodeIds);
    mutate({
      ...graph,
      nodes: graph.nodes.filter((n) => !gone.has(n.id)),
      // A link to a removed task would dangle, so it goes with it.
      edges: graph.edges.filter(
        (e) => !edgeIds.includes(e.id) && !gone.has(e.source) && !gone.has(e.target)),
    });
    setSelectedNodeId(null);
    setSelectedEdgeId(null);
  }

  function setEdgeCondition(edgeId: string, condition: EdgeCondition) {
    mutate({
      ...graph,
      edges: graph.edges.map((e) => (e.id === edgeId ? { ...e, condition } : e)),
    });
  }

  // ── Save ─────────────────────────────────────────────────────────
  async function save() {
    setSaving(true);
    setError(null);
    try {
      const body = { groupId, name, description: description || null, graph, enabled };
      const saved: Workflow = editing && revision !== null
        ? await workflowsApi.update(workflowId!, { ...body, revision })
        : await workflowsApi.create(body);
      setDirty(false);
      navigate(`/workflows/${saved.workflowId}`);
    } catch (e) {
      const v = validationOf(e);
      if (v) setValidation(v);
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  // ── Derived ──────────────────────────────────────────────────────
  const selectedNode = graph.nodes.find((n) => n.id === selectedNodeId) ?? null;
  const selectedEdge = graph.edges.find((e) => e.id === selectedEdgeId) ?? null;

  /** Validation messages keyed by the task they name, so the canvas can mark them. */
  const problems = useMemo(() => {
    const out: Record<string, string[]> = {};
    for (const message of validation?.errors ?? []) {
      const match = /task '([^']+)'/.exec(message);
      if (!match) continue;
      const node = graph.nodes.find((n) => n.name === match[1] || n.id === match[1]);
      if (!node) continue;
      (out[node.id] ??= []).push(message);
    }
    return out;
  }, [validation, graph.nodes]);

  const regions = (group?.capacity ?? []).map((c) => ({ region: c.region, maxAvailable: c.maxAvailable }));
  const canSave = name.trim().length > 0 && graph.nodes.length > 0 && !saving;

  return (
    <section className="workflowsPage workflowBuilder">
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <h1>{editing ? "Edit workflow" : "New workflow"}</h1>
          <small className="ink-soft">
            <Link to="/workflows">Workflows</Link>
            {groupId && <> · <Link to={`/workflows/groups/${encodeURIComponent(groupId)}`}>{group?.name ?? groupId}</Link></>}
          </small>
        </div>
        <div className="pageHeader__actions">
          <button
            type="button" className="btn btn--ghost"
            onClick={() => mutate(autoArrange(graph))}
            disabled={graph.nodes.length === 0}
          >Auto arrange</button>
          <button type="button" className="btn btn--primary" disabled={!canSave} onClick={() => void save()}>
            {saving ? "Saving…" : "Save workflow"}
          </button>
        </div>
      </header>

      {error && <div className="banner banner--error" role="alert">{error}</div>}

      <div className="workflowBuilder__meta">
        <label className="field">
          <span>Name</span>
          <input
            type="text" value={name} maxLength={255} placeholder="Nightly regression"
            onChange={(e) => { setName(e.target.value); setDirty(true); }}
          />
        </label>
        <label className="field">
          <span>Description</span>
          <input
            type="text" value={description} placeholder="What this workflow is for"
            onChange={(e) => { setDescription(e.target.value); setDirty(true); }}
          />
        </label>
        <label className="field field--check">
          <input type="checkbox" checked={enabled} onChange={(e) => { setEnabled(e.target.checked); setDirty(true); }} />
          <span>Enabled</span>
        </label>
      </div>

      <div className="workflowBuilder__layout">
        <aside className="workflowBuilder__palette">
          <div className="formField__labelRow">
            <h2>Add a task</h2>
            <InfoTip label="About linking tasks">
              Drag from the bottom of one task to the top of another to link them;
              two links out of one task run their targets at the same time.
            </InfoTip>
          </div>
          {PALETTE.map((type) => (
            <button key={type} type="button" className="paletteButton" onClick={() => addNode(type)}>
              <span className={`paletteButton__swatch paletteButton__swatch--${type.toLowerCase()}`} aria-hidden="true" />
              {NODE_LABELS[type]}
            </button>
          ))}
        </aside>

        <div className="workflowBuilder__canvas">
          <WorkflowCanvas
            graph={graph}
            problems={problems}
            editable
            selectedNodeId={selectedNodeId}
            onSelectNode={(id) => { setSelectedNodeId(id); if (id) setSelectedEdgeId(null); }}
            onSelectEdge={(id) => { setSelectedEdgeId(id); if (id) setSelectedNodeId(null); }}
            onConnect={connect}
            onDeleteSelection={deleteSelection}
            onNodesMoved={(positions) => mutate({
              ...graph,
              nodes: graph.nodes.map((n) => (positions[n.id] ? { ...n, position: positions[n.id] } : n)),
            })}
            height={520}
          />
        </div>

        <aside className="workflowBuilder__side">
          {selectedNode ? (
            <NodeEditor
              node={selectedNode}
              applications={applications}
              templates={templates}
              regions={regions}
              groupNotify={{
                to: group?.notifyTo ?? [], cc: group?.notifyCc ?? [], bcc: group?.notifyBcc ?? [],
              }}
              onChange={(next) => mutate({
                ...graph,
                nodes: graph.nodes.map((n) => (n.id === next.id ? next : n)),
              })}
              onDelete={() => deleteSelection([selectedNode.id], [])}
            />
          ) : selectedEdge ? (
            <div className="nodeEditor">
              <div className="nodeEditor__head">
                <h2>Link settings</h2>
                <button
                  type="button" className="btn btn--ghost btn--sm"
                  onClick={() => deleteSelection([], [selectedEdge.id])}
                >Remove link</button>
              </div>
              <p className="ink-soft" style={{ fontSize: "0.85rem" }}>
                <span className="mono">{selectedEdge.source}</span> →{" "}
                <span className="mono">{selectedEdge.target}</span>
              </p>
              <label className="field">
                <span>Run the next task</span>
                <select
                  value={selectedEdge.condition ?? "ON_SUCCESS"}
                  onChange={(e) => setEdgeCondition(selectedEdge.id, e.target.value as EdgeCondition)}
                >
                  {(["ON_SUCCESS", "ON_FAILURE", "ALWAYS"] as EdgeCondition[]).map((c) => (
                    <option key={c} value={c}>{EDGE_LABELS[c]}</option>
                  ))}
                </select>
              </label>
              <p className="ink-soft" style={{ fontSize: "0.82rem" }}>
                {selectedEdge.condition === "ON_FAILURE"
                  ? "Runs only when the task above fails — use it to alert someone or clean up. The run still ends as Failed."
                  : selectedEdge.condition === "ALWAYS"
                    ? "Runs whether the task above succeeded or failed. A task that was skipped never ran, so it does not trigger this."
                    : "Runs only when the task above succeeds."}
              </p>
            </div>
          ) : (
            <ValidationPanel validation={validation} graph={graph} />
          )}
          <CapacityPanel validation={validation} groupId={groupId} />
        </aside>
      </div>

      {dirty && (
        <p className="ink-soft" style={{ fontSize: "0.8rem" }}>Unsaved changes.</p>
      )}
    </section>
  );
}

/** What is wrong and what is risky, shown whenever nothing is selected. */
function ValidationPanel({ validation, graph }: {
  validation: WorkflowValidation | null;
  graph: WorkflowGraph;
}) {
  if (graph.nodes.length === 0) {
    return (
      <div className="card">
        <h2>Getting started</h2>
        <p className="ink-soft">
          Add a task from the left. A typical shape is a health check, then the load
          tests it gates, then an email — link them and the order takes care of itself.
        </p>
      </div>
    );
  }
  if (!validation) {
    return <div className="card"><h2>Checks</h2><p className="ink-soft">Checking…</p></div>;
  }
  if (validation.valid && validation.warnings.length === 0) {
    return (
      <div className="card">
        <h2>Checks</h2>
        <p className="ink-ok">Ready to run.</p>
      </div>
    );
  }
  return (
    <div className="card">
      <h2>Checks</h2>
      {validation.errors.length > 0 && (
        <ul className="checkList checkList--error">
          {validation.errors.map((m) => <li key={m}>{m}</li>)}
        </ul>
      )}
      {validation.warnings.length > 0 && (
        <ul className="checkList checkList--warn">
          {validation.warnings.map((m) => <li key={m}>{m}</li>)}
        </ul>
      )}
    </div>
  );
}
