/**
 * Typed client for the workflow surface — `/api/v1/workflows/*` and
 * `/api/v1/workflowExecutions/*`.
 *
 * <p>`WorkflowGraph` is the wire shape the builder posts and the backend
 * stores verbatim, so the node types here mirror
 * `jmeter-global-orchestrator`'s sealed `WorkflowNode` hierarchy exactly —
 * keep the field names aligned or a saved canvas silently loses config.
 * React Flow's own per-node keys are stripped before a save (see
 * `lib/workflowGraph.ts`); the backend ignores unknown fields either way.
 */

import { GlobalOrchestratorError, request } from "./runs";

export type NodeType = "HEALTH_CHECK" | "LOAD_TEST" | "EMAIL" | "DELAY" | "APPROVAL";
export type EdgeCondition = "ON_SUCCESS" | "ON_FAILURE" | "ALWAYS";
export type JoinPolicy = "ALL" | "ANY";
export type ExecutionState = "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export type TaskState =
  | "PENDING" | "RUNNING" | "AWAITING_APPROVAL"
  | "SUCCEEDED" | "FAILED" | "SKIPPED" | "CANCELLED";

export interface NodePosition {
  x: number;
  y: number;
}

export interface RegionCount {
  region: string;
  count: number;
}

/** Common to every node; the per-type fields sit alongside, discriminated on `type`. */
interface BaseNode {
  id: string;
  name: string;
  position: NodePosition;
  joinPolicy?: JoinPolicy;
}

export interface HealthCheckNode extends BaseNode {
  type: "HEALTH_CHECK";
  application: string;
  requirement?: "ALL" | "ANY" | "AT_LEAST";
  minHealthy?: number | null;
  attempts?: number;
  intervalSeconds?: number;
  timeoutSeconds?: number;
}

export interface LoadTestNode extends BaseNode {
  type: "LOAD_TEST";
  application: string;
  templateBlobId: string;
  /** Required and authoritative: the node pins the fleet, the template supplies the plan. */
  fleetAllocation: RegionCount[];
  properties?: Record<string, string>;
  saveResults?: boolean | null;
  successWhen?: "COMPLETED_ONLY" | "ANY_TERMINAL";
  maxDurationMinutes?: number;
}

export interface EmailNode extends BaseNode {
  type: "EMAIL";
  /** Empty inherits the group's notifyTo; cc and bcc inherit theirs the same way. */
  to?: string[];
  cc?: string[];
  bcc?: string[];
  subject: string;
  body: string;
  includeSummary?: boolean;
}

export interface DelayNode extends BaseNode {
  type: "DELAY";
  seconds: number;
}

export interface ApprovalNode extends BaseNode {
  type: "APPROVAL";
  instructions?: string;
  deadlineMinutes?: number | null;
}

export type WorkflowNode =
  | HealthCheckNode | LoadTestNode | EmailNode | DelayNode | ApprovalNode;

export interface WorkflowEdge {
  id: string;
  source: string;
  target: string;
  condition?: EdgeCondition;
}

export interface WorkflowGraph {
  v: 1;
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
}

/** Tasks one canvas may hold; the backend rejects more. */
export const MAX_NODES = 64;
/** Load tests one canvas may hold — the bound that keeps the capacity analysis exact. */
export const MAX_LOAD_TEST_NODES = 16;

export interface WorkflowExecutionSummary {
  executionId: string;
  state: ExecutionState;
  startedAt: string;
  completedAt?: string | null;
}

export interface Workflow {
  workflowId: string;
  groupId: string;
  name: string;
  description?: string | null;
  graph: WorkflowGraph;
  enabled: boolean;
  /** Optimistic lock — send the value you loaded on save. */
  revision: number;
  createdBy?: string | null;
  createdAt: string;
  updatedBy?: string | null;
  updatedAt: string;
  lastExecution?: WorkflowExecutionSummary | null;
}

export interface WorkflowTask {
  taskId: string;
  executionId: string;
  nodeId: string;
  type: NodeType;
  name: string;
  state: TaskState;
  attempt: number;
  applicationName?: string | null;
  runId?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  dueAt?: string | null;
  result?: Record<string, unknown> | null;
  errorReason?: string | null;
}

/** What a workflow delete actually did. */
export interface DeleteWorkflowResult {
  cancelledExecutions: number;
  deletedExecutions: number;
}

export interface WorkflowExecution {
  executionId: string;
  workflowId: string;
  groupId: string;
  /** Snapshot at launch — a rename never rewrites history. */
  workflowName: string;
  graph: WorkflowGraph;
  state: ExecutionState;
  stateReason?: string | null;
  triggeredBy: string;
  startedAt: string;
  completedAt?: string | null;
  nextTickAt?: string | null;
  /** Archived when set — off the default history, still readable by id. */
  hiddenAt?: string | null;
  tasks: WorkflowTask[];
}

export interface WorkflowGroupSummary {
  groupId: string;
  name: string;
  description?: string | null;
  teamName?: string | null;
  workflowCount: number;
  notifyTo: string[];
  notifyCc: string[];
  notifyBcc: string[];
}

/** One cluster's demand picture: the most this graph can want, and what is reserved. */
export interface RegionDemand {
  region: string;
  peakWorkers: number;
  /** The tasks that make up the peak — why the number is what it is. */
  tasks: string[];
  reserved: number;
  fits: boolean;
}

export interface WorkflowValidation {
  valid: boolean;
  errors: string[];
  /** Never block a save; over-subscribed capacity lands here. */
  warnings: string[];
  capacity: RegionDemand[];
}

export interface SaveWorkflowRequest {
  groupId?: string;
  name: string;
  description?: string | null;
  graph: WorkflowGraph;
  enabled?: boolean;
  /** Required on update: the revision the editor loaded. */
  revision?: number;
}

/** True when a failed save/launch was a capacity refusal, so the UI can show the clusters. */
export function isCapacityError(e: unknown): e is GlobalOrchestratorError {
  return e instanceof GlobalOrchestratorError && e.code === "WORKFLOW_CAPACITY_EXCEEDED";
}

/** True when the graph itself was rejected — `validationOf` carries the violations. */
export function isInvalidError(e: unknown): e is GlobalOrchestratorError {
  return e instanceof GlobalOrchestratorError && e.code === "WORKFLOW_INVALID";
}

/** The validation body a 400 WORKFLOW_INVALID carries, if any. */
export function validationOf(e: unknown): WorkflowValidation | null {
  if (!(e instanceof GlobalOrchestratorError)) return null;
  const extra = e.extra as { validation?: WorkflowValidation } | undefined;
  return extra?.validation ?? null;
}

/** The clusters a 409 WORKFLOW_CAPACITY_EXCEEDED names. */
export function clustersOf(e: unknown): RegionDemand[] {
  if (!(e instanceof GlobalOrchestratorError)) return [];
  const extra = e.extra as { clusters?: RegionDemand[] } | undefined;
  return extra?.clusters ?? [];
}

export const workflowsApi = {
  /** The landing surface: every group, its owner and how many workflows it holds. */
  groups(signal?: AbortSignal): Promise<WorkflowGroupSummary[]> {
    return request<WorkflowGroupSummary[]>("GET", "/api/v1/workflows/groups", undefined, signal);
  },

  list(groupId: string, signal?: AbortSignal): Promise<Workflow[]> {
    return request<Workflow[]>(
      "GET", `/api/v1/workflows?groupId=${encodeURIComponent(groupId)}`, undefined, signal);
  },

  get(workflowId: string, signal?: AbortSignal): Promise<Workflow> {
    return request<Workflow>("GET", `/api/v1/workflows/${workflowId}`, undefined, signal);
  },

  /** Errors, warnings and the peak-workers picture, without saving anything. */
  validate(groupId: string, graph: WorkflowGraph, signal?: AbortSignal): Promise<WorkflowValidation> {
    return request<WorkflowValidation>(
      "POST", "/api/v1/workflows/validate", { groupId, graph }, signal);
  },

  create(req: SaveWorkflowRequest): Promise<Workflow> {
    return request<Workflow>("POST", "/api/v1/workflows", req);
  },

  /** `revision` must be the one loaded; a stale value is 409 WORKFLOW_REVISION_CONFLICT. */
  update(workflowId: string, req: SaveWorkflowRequest): Promise<Workflow> {
    return request<Workflow>("PUT", `/api/v1/workflows/${workflowId}`, req);
  },

  /**
   * Delete the workflow and its runs. `cancelRunning` stops an execution in
   * progress first — without it, one still going refuses the delete.
   */
  remove(workflowId: string, cancelRunning = false): Promise<DeleteWorkflowResult> {
    return request<DeleteWorkflowResult>(
      "DELETE", `/api/v1/workflows/${workflowId}?cancelRunning=${cancelRunning}`);
  },

  /** 409 WORKFLOW_CAPACITY_EXCEEDED when the graph's peak exceeds the group's reservation. */
  launch(workflowId: string): Promise<WorkflowExecution> {
    return request<WorkflowExecution>("POST", `/api/v1/workflows/${workflowId}/executions`);
  },

  /** `archived` reads the archive instead of the history; the two are never mixed. */
  history(workflowId: string, limit = 25, archived = false, signal?: AbortSignal): Promise<WorkflowExecution[]> {
    return request<WorkflowExecution[]>(
      "GET", `/api/v1/workflows/${workflowId}/executions?limit=${limit}&archived=${archived}`,
      undefined, signal);
  },

  archivedCount(workflowId: string, signal?: AbortSignal): Promise<{ archived: number }> {
    return request<{ archived: number }>(
      "GET", `/api/v1/workflows/${workflowId}/executions/archivedCount`, undefined, signal);
  },

  /** Archive finished runs; one still going is skipped rather than refused. */
  archiveRuns(workflowId: string, executionIds: string[]): Promise<{ archived: number }> {
    return request<{ archived: number }>(
      "POST", `/api/v1/workflows/${workflowId}/executions/archive`, { executionIds });
  },

  restoreRuns(workflowId: string, executionIds: string[]): Promise<{ restored: number }> {
    return request<{ restored: number }>(
      "POST", `/api/v1/workflows/${workflowId}/executions/restore`, { executionIds });
  },

  /** Permanent, and only for runs already archived. */
  deleteRuns(workflowId: string, executionIds: string[]): Promise<{ deleted: number }> {
    return request<{ deleted: number }>(
      "POST", `/api/v1/workflows/${workflowId}/executions/delete`, { executionIds });
  },

  execution(executionId: string, signal?: AbortSignal): Promise<WorkflowExecution> {
    return request<WorkflowExecution>(
      "GET", `/api/v1/workflowExecutions/${executionId}`, undefined, signal);
  },

  /** Run ids in node order — what the execution's metrics panel charts. */
  executionRuns(executionId: string, signal?: AbortSignal): Promise<string[]> {
    return request<string[]>(
      "GET", `/api/v1/workflowExecutions/${executionId}/runs`, undefined, signal);
  },

  cancel(executionId: string): Promise<WorkflowExecution> {
    return request<WorkflowExecution>("POST", `/api/v1/workflowExecutions/${executionId}/cancel`);
  },

  approve(executionId: string, taskId: string, note?: string): Promise<WorkflowExecution> {
    return request<WorkflowExecution>(
      "POST", `/api/v1/workflowExecutions/${executionId}/tasks/${taskId}/approve`, { note });
  },

  reject(executionId: string, taskId: string, note?: string): Promise<WorkflowExecution> {
    return request<WorkflowExecution>(
      "POST", `/api/v1/workflowExecutions/${executionId}/tasks/${taskId}/reject`, { note });
  },
};
