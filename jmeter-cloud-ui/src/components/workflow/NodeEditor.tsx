import { useEffect, useState } from "react";

import type {
  ApprovalNode, DelayNode, EmailNode, HealthCheckNode, LoadTestNode, WorkflowNode,
} from "../../api/workflows";
import type { Application } from "../../api/applications";
import type { TemplateSummary } from "../../api/templates";
import { InfoTip } from "../InfoTip";

/**
 * The selected task's settings. Every control writes the whole node back
 * through {@link NodeEditorProps.onChange}, so the builder holds one graph and
 * the canvas re-derives — there is no second copy of a task to drift.
 */
export interface NodeEditorProps {
  node: WorkflowNode;
  /** Applications in this workflow's group; a task may only name one of these. */
  applications: Application[];
  templates: TemplateSummary[];
  /** Clusters the group has reserved capacity in — the only ones a load test may use. */
  regions: { region: string; maxAvailable: number }[];
  /** Group defaults an email task inherits when it names no recipients of its own. */
  groupNotify: { to: string[]; cc: string[]; bcc: string[] };
  onChange: (next: WorkflowNode) => void;
  onDelete: () => void;
}

export function NodeEditor(props: NodeEditorProps) {
  const { node, onChange, onDelete } = props;

  return (
    <div className="nodeEditor">
      <div className="nodeEditor__head">
        <h2>Task settings</h2>
        <button type="button" className="btn btn--ghost btn--sm" onClick={onDelete}>Remove task</button>
      </div>

      <label className="field">
        <span>Name</span>
        <input
          type="text"
          value={node.name}
          maxLength={255}
          onChange={(e) => onChange({ ...node, name: e.target.value })}
        />
      </label>

      <label className="field">
        <span>
          Task id
          <InfoTip label="About the task id">
            Used in email placeholders — <code>{"${task." + node.id + ".state}"}</code>.
          </InfoTip>
        </span>
        <input type="text" value={node.id} readOnly className="mono" />
      </label>

      <label className="field">
        <span>
          When several links arrive
          <InfoTip label="About join behaviour">
            <b>All</b> waits for every incoming link to be satisfied. <b>Any</b> starts on the first one.
          </InfoTip>
        </span>
        <select
          value={node.joinPolicy ?? "ALL"}
          onChange={(e) => onChange({ ...node, joinPolicy: e.target.value as "ALL" | "ANY" })}
        >
          <option value="ALL">Wait for all of them</option>
          <option value="ANY">Start on any one</option>
        </select>
      </label>

      <hr className="nodeEditor__rule" />

      {node.type === "HEALTH_CHECK" && <HealthCheckFields {...props} node={node} />}
      {node.type === "LOAD_TEST" && <LoadTestFields {...props} node={node} />}
      {node.type === "EMAIL" && <EmailFields {...props} node={node} />}
      {node.type === "DELAY" && <DelayFields node={node} onChange={onChange} />}
      {node.type === "APPROVAL" && <ApprovalFields node={node} onChange={onChange} />}
    </div>
  );
}

function HealthCheckFields({ node, applications, onChange }:
  NodeEditorProps & { node: HealthCheckNode }) {
  const app = applications.find((a) => a.name === node.application);
  const endpointCount = app?.healthEndpoints?.length ?? 0;
  return (
    <>
      <label className="field">
        <span>Application</span>
        <select value={node.application} onChange={(e) => onChange({ ...node, application: e.target.value })}>
          <option value="">Choose an application…</option>
          {applications.map((a) => (
            <option key={a.applicationId} value={a.name}>{a.name}</option>
          ))}
        </select>
      </label>
      {node.application && (
        <p className={endpointCount === 0 ? "ink-warn" : "ink-soft"} style={{ fontSize: "0.82rem" }}>
          {endpointCount === 0
            ? "This application has no health endpoints — the check will fail. Add them on the application page."
            : `${endpointCount} endpoint${endpointCount === 1 ? "" : "s"} configured; each is probed live, not read from the last poll.`}
        </p>
      )}

      <label className="field">
        <span>Passes when</span>
        <select
          value={node.requirement ?? "ALL"}
          onChange={(e) => onChange({ ...node, requirement: e.target.value as HealthCheckNode["requirement"] })}
        >
          <option value="ALL">Every endpoint is healthy</option>
          <option value="ANY">At least one is healthy</option>
          <option value="AT_LEAST">At least N are healthy</option>
        </select>
      </label>
      {node.requirement === "AT_LEAST" && (
        <label className="field">
          <span>Minimum healthy</span>
          <input
            type="number" min={1} max={99}
            value={node.minHealthy ?? 1}
            onChange={(e) => onChange({ ...node, minHealthy: Number(e.target.value) })}
          />
        </label>
      )}

      <div className="fieldRow">
        <label className="field">
          <span>
            Attempts
            <InfoTip label="About attempts">Retries span engine ticks, so a long retry budget costs nothing while it waits.</InfoTip>
          </span>
          <input
            type="number" min={1} max={10}
            value={node.attempts ?? 1}
            onChange={(e) => onChange({ ...node, attempts: Number(e.target.value) })}
          />
        </label>
        <label className="field">
          <span>Gap (seconds)</span>
          <input
            type="number" min={5} max={300}
            value={node.intervalSeconds ?? 15}
            onChange={(e) => onChange({ ...node, intervalSeconds: Number(e.target.value) })}
          />
        </label>
        <label className="field">
          <span>Timeout (seconds)</span>
          <input
            type="number" min={1} max={30}
            value={node.timeoutSeconds ?? 5}
            onChange={(e) => onChange({ ...node, timeoutSeconds: Number(e.target.value) })}
          />
        </label>
      </div>
    </>
  );
}

function LoadTestFields({ node, applications, templates, regions, onChange }:
  NodeEditorProps & { node: LoadTestNode }) {
  function setRegion(region: string, count: number) {
    const rest = node.fleetAllocation.filter((f) => f.region !== region);
    const next = count > 0 ? [...rest, { region, count }] : rest;
    next.sort((a, b) => a.region.localeCompare(b.region));
    onChange({ ...node, fleetAllocation: next });
  }
  const total = node.fleetAllocation.reduce((sum, f) => sum + f.count, 0);

  return (
    <>
      <label className="field">
        <span>Application</span>
        <select value={node.application} onChange={(e) => onChange({ ...node, application: e.target.value })}>
          <option value="">Choose an application…</option>
          {applications.map((a) => (
            <option key={a.applicationId} value={a.name}>{a.name}</option>
          ))}
        </select>
      </label>

      <label className="field">
        <span>
          Template
          <InfoTip label="About the template">Supplies the test plan, data files and plugins. The workers below are this task's, not the template's.</InfoTip>
        </span>
        <select
          value={node.templateBlobId}
          onChange={(e) => onChange({ ...node, templateBlobId: e.target.value })}
        >
          <option value="">Choose a template…</option>
          {templates.map((t) => (
            <option key={t.blobId} value={t.blobId}>
              {t.name}{t.application ? ` (${t.application})` : ""}
            </option>
          ))}
        </select>
      </label>

      <fieldset className="field">
        <legend>
          Workers
          <InfoTip label="About workers">
            Counted against the group's reservation. Tasks that can run at the same
            time are added together — that total is the "peak" shown beside the canvas.
          </InfoTip>
        </legend>
        {regions.length === 0 ? (
          <p className="ink-warn" style={{ fontSize: "0.82rem" }}>
            This group has no reserved capacity yet, so a load test cannot run.
          </p>
        ) : (
          regions.map((r) => {
            const current = node.fleetAllocation.find((f) => f.region === r.region)?.count ?? 0;
            return (
              <div className="fieldRow fieldRow--tight" key={r.region}>
                <span className="mono">{r.region}</span>
                <input
                  type="number" min={0} max={r.maxAvailable}
                  value={current}
                  onChange={(e) => setRegion(r.region, Math.max(0, Number(e.target.value)))}
                />
                <span className="ink-soft" style={{ fontSize: "0.8rem" }}>of {r.maxAvailable} reserved</span>
              </div>
            );
          })
        )}
        <p className="ink-soft" style={{ fontSize: "0.82rem" }}>
          {total === 0 ? "Allocate at least one worker." : `${total} worker${total === 1 ? "" : "s"} total.`}
        </p>
      </fieldset>

      <div className="fieldRow">
        <label className="field">
          <span>Passes when</span>
          <select
            value={node.successWhen ?? "COMPLETED_ONLY"}
            onChange={(e) => onChange({ ...node, successWhen: e.target.value as LoadTestNode["successWhen"] })}
          >
            <option value="COMPLETED_ONLY">The run completes</option>
            <option value="ANY_TERMINAL">However the run ends</option>
          </select>
        </label>
        <label className="field">
          <span>
            Give up after (minutes)
            <InfoTip label="About the time limit">The run is aborted and this task fails if it overruns.</InfoTip>
          </span>
          <input
            type="number" min={1} max={1440}
            value={node.maxDurationMinutes ?? 120}
            onChange={(e) => onChange({ ...node, maxDurationMinutes: Number(e.target.value) })}
          />
        </label>
      </div>

      <label className="field">
        <span>
          Save results
        </span>
        <select
          value={node.saveResults == null ? "" : String(node.saveResults)}
          onChange={(e) => onChange({
            ...node,
            saveResults: e.target.value === "" ? null : e.target.value === "true",
          })}
        >
          <option value="">Use the template's setting</option>
          <option value="true">Yes</option>
          <option value="false">No</option>
        </select>
      </label>

      <PropertiesEditor
        properties={node.properties ?? {}}
        onChange={(properties) => onChange({ ...node, properties })}
      />
    </>
  );
}

/** `-J` overrides layered over the template's globals; empty means "just the template's". */
function PropertiesEditor({ properties, onChange }: {
  properties: Record<string, string>;
  onChange: (next: Record<string, string>) => void;
}) {
  const entries = Object.entries(properties);
  return (
    <fieldset className="field">
      <legend>
        Property overrides
        <InfoTip label="About property overrides">JMeter <code>-J</code> values layered over the template's. Leave empty to use the template as saved.</InfoTip>
      </legend>
      {entries.map(([key, value], i) => (
        <div className="fieldRow fieldRow--tight" key={i}>
          <input
            type="text" placeholder="name" value={key} className="mono"
            onChange={(e) => {
              const next: Record<string, string> = {};
              entries.forEach(([k, v], j) => { next[j === i ? e.target.value : k] = v; });
              onChange(next);
            }}
          />
          <input
            type="text" placeholder="value" value={value}
            onChange={(e) => onChange({ ...properties, [key]: e.target.value })}
          />
          <button
            type="button" className="btn btn--ghost btn--sm"
            onClick={() => {
              const next = { ...properties };
              delete next[key];
              onChange(next);
            }}
          >Remove</button>
        </div>
      ))}
      <button
        type="button" className="btn btn--ghost btn--sm"
        // A blank row already IS the empty key, so a second click would be a
        // silent no-op; disable rather than look broken.
        disabled={Object.prototype.hasOwnProperty.call(properties, "")}
        onClick={() => onChange({ ...properties, "": "" })}
      >+ Add property</button>
    </fieldset>
  );
}

/** Comma-separated text to the list the API takes; blanks dropped. */
function parseAddresses(raw: string): string[] {
  return raw.split(",").map((s) => s.trim()).filter(Boolean);
}

/**
 * An address box that keeps the operator's raw text.
 *
 * <p>A fully controlled `value={list.join(", ")}` cannot work here: the comma
 * the operator just typed parses to nothing, so re-rendering from the list
 * erases it and multi-recipient entry becomes impossible. The text is local,
 * and only re-syncs when the committed list stops matching it — which happens
 * when the selection changes, never while typing.
 */
function AddressInput({ value, placeholder, onChange }: {
  value: string[];
  placeholder: string;
  onChange: (next: string[]) => void;
}) {
  const [raw, setRaw] = useState(() => value.join(", "));
  const committed = value.join("\u0000");

  useEffect(() => {
    if (parseAddresses(raw).join("\u0000") !== committed) {
      setRaw(value.join(", "));
    }
    // Only when the committed list changes underneath us; `raw` is deliberately
    // not a dependency, or every keystroke would fight the box.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [committed]);

  return (
    <input
      type="text"
      value={raw}
      placeholder={placeholder}
      onChange={(e) => {
        setRaw(e.target.value);
        onChange(parseAddresses(e.target.value));
      }}
    />
  );
}

function EmailFields({ node, groupNotify, onChange }: NodeEditorProps & { node: EmailNode }) {
  const inherits = (list: string[] | undefined, fallback: string[]) =>
    (list ?? []).length === 0 && fallback.length > 0
      ? `inherits the group's: ${fallback.join(", ")}`
      : (list ?? []).length === 0 ? "none" : null;

  return (
    <>
      {(["to", "cc", "bcc"] as const).map((field) => {
        const fallback = groupNotify[field];
        const hint = inherits(node[field], fallback);
        return (
          <label className="field" key={field}>
            <span>
              {field === "to" ? "To" : field === "cc" ? "Cc" : "Bcc"}
              <InfoTip label="About recipients">Comma-separated. Leave empty to use the group's default recipients.</InfoTip>
            </span>
            <AddressInput
              value={node[field] ?? []}
              placeholder={fallback.length > 0 ? fallback.join(", ") : "name@example.com"}
              onChange={(next) => onChange({ ...node, [field]: next })}
            />
            {hint && <small className="ink-soft">{hint}</small>}
          </label>
        );
      })}

      <label className="field">
        <span>
          Subject
          <InfoTip label="About placeholders">
            Placeholders: <code>{"${workflow.name}"}</code>, <code>{"${execution.state}"}</code>,{" "}
            <code>{"${applications}"}</code>, <code>{"${task.<id>.state}"}</code>.
          </InfoTip>
        </span>
        <input
          type="text" value={node.subject} maxLength={255}
          onChange={(e) => onChange({ ...node, subject: e.target.value })}
        />
      </label>

      <label className="field">
        <span>Message</span>
        <textarea
          rows={6} value={node.body}
          placeholder="All health checks passed for ${applications}. Starting the performance test now."
          onChange={(e) => onChange({ ...node, body: e.target.value })}
        />
      </label>

      <label className="field field--check">
        <input
          type="checkbox" checked={node.includeSummary ?? false}
          onChange={(e) => onChange({ ...node, includeSummary: e.target.checked })}
        />
        <span>Append a table of every task's state and result</span>
      </label>
    </>
  );
}

function DelayFields({ node, onChange }: { node: DelayNode; onChange: (n: WorkflowNode) => void }) {
  return (
    <label className="field">
      <span>Wait for (seconds)</span>
      <input
        type="number" min={1} max={86400} value={node.seconds}
        onChange={(e) => onChange({ ...node, seconds: Number(e.target.value) })}
      />
    </label>
  );
}

function ApprovalFields({ node, onChange }: { node: ApprovalNode; onChange: (n: WorkflowNode) => void }) {
  return (
    <>
      <label className="field">
        <span>What are you asking?</span>
        <textarea
          rows={3} value={node.instructions ?? ""}
          placeholder="Confirm the change window is open before the load starts."
          onChange={(e) => onChange({ ...node, instructions: e.target.value })}
        />
      </label>
      <label className="field">
        <span>
          Give up after (minutes)
          <InfoTip label="About the approval deadline">Leave empty to wait indefinitely. On timeout the task fails, so an "on failure" link is how you handle it.</InfoTip>
        </span>
        <input
          type="number" min={1} max={10080}
          value={node.deadlineMinutes ?? ""}
          onChange={(e) => onChange({
            ...node,
            deadlineMinutes: e.target.value === "" ? null : Number(e.target.value),
          })}
        />
      </label>
    </>
  );
}
