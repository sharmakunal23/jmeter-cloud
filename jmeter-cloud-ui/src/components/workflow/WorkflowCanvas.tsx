import { useEffect, useMemo } from "react";
import {
  Background, Controls, ReactFlow, ReactFlowProvider,
  useEdgesState, useNodesState, useReactFlow,
  type Connection, type Edge, type Node, type OnConnect,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import type { WorkflowGraph } from "../../api/workflows";
import { NODE_HEIGHT, toFlowEdges, toFlowNodes, type FlowNodeData } from "../../lib/workflowGraph";
import { WorkflowTaskNode } from "./WorkflowTaskNode";

const nodeTypes = { workflowTask: WorkflowTaskNode };

const MIN_CANVAS_H = 420;
const MAX_CANVAS_H = 900;

/** The height at which this graph's fitView stays readable. */
function measuredHeight(graph: WorkflowGraph): number {
    if (graph.nodes.length === 0) return MIN_CANVAS_H;
    let top = Infinity;
    let bottom = -Infinity;
    for (const n of graph.nodes) {
        const y = n.position?.y ?? 0;
        top = Math.min(top, y);
        bottom = Math.max(bottom, y + NODE_HEIGHT);
    }
    const extent = bottom - top;
    // 0.75 keeps the fit comfortably readable without handing a short graph a
    // wall of empty canvas.
    return Math.round(Math.min(MAX_CANVAS_H, Math.max(MIN_CANVAS_H, extent * 0.75 + 80)));
}

export interface WorkflowCanvasProps {
  graph: WorkflowGraph;
  /** nodeId → task state; supplied on an execution view to tint the nodes. */
  states?: Record<string, string>;
  /** nodeId → validation messages; marks the task and shows them on hover. */
  problems?: Record<string, string[]>;
  /** Read-only canvases still pan, zoom and select — they just cannot be rewired. */
  editable?: boolean;
  selectedNodeId?: string | null;
  onSelectNode?: (nodeId: string | null) => void;
  onSelectEdge?: (edgeId: string | null) => void;
  /** Editable only: the operator moved a task, or drew/removed a link. */
  onNodesMoved?: (positions: Record<string, { x: number; y: number }>) => void;
  onConnect?: (connection: Connection) => void;
  onDeleteSelection?: (nodeIds: string[], edgeIds: string[]) => void;
  /** Fixed height; omitted lets the canvas size itself to the graph. */
  height?: number;
}

export function WorkflowCanvas(props: WorkflowCanvasProps) {
  return (
    <ReactFlowProvider>
      <CanvasInner {...props} />
    </ReactFlowProvider>
  );
}

function CanvasInner({
  graph, states, problems, editable = false, selectedNodeId,
  onSelectNode, onSelectEdge, onNodesMoved, onConnect, onDeleteSelection, height,
}: WorkflowCanvasProps) {
  const [nodes, setNodes, onNodesChange] = useNodesState<Node<FlowNodeData>>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const { fitView } = useReactFlow();

  // The graph prop is the source of truth: the builder edits it and hands back
  // a new one, so the canvas re-derives rather than keeping a second copy that
  // could drift from what a save would write.
  const flowNodes = useMemo(() => toFlowNodes(graph, { states, problems }), [graph, states, problems]);
  const flowEdges = useMemo(() => toFlowEdges(graph), [graph]);

  useEffect(() => {
    setNodes(flowNodes.map((n) => ({ ...n, selected: n.id === selectedNodeId })));
  }, [flowNodes, selectedNodeId, setNodes]);

  useEffect(() => {
    setEdges(flowEdges);
  }, [flowEdges, setEdges]);

  // Frame the graph once it has nodes; a later edit keeps the operator's viewport.
  const nodeCount = graph.nodes.length;
  useEffect(() => {
    if (nodeCount === 0) return;
    const t = window.setTimeout(() => fitView({ padding: 0.15, duration: 200 }), 50);
    return () => window.clearTimeout(t);
    // Deliberately only on the first render with nodes and on count changes:
    // re-framing while someone is dragging is the most annoying thing a canvas can do.
  }, [nodeCount, fitView]);

  const handleConnect: OnConnect = (connection) => {
    if (editable) onConnect?.(connection);
  };

  // Size the canvas to the graph's own extent, not to a node count: fitting a
  // tall graph into a short box is arithmetically correct and useless, because
  // it zooms out past the point where a task's name can be read. Giving it
  // roughly the height it wants lands the fit near 1:1; past the cap the page
  // would scroll more than it is worth, and panning takes over.
  const measured = height ?? measuredHeight(graph);

  return (
    <div className="wfCanvas" style={{ height: measured }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodesChange={editable ? onNodesChange : undefined}
        onEdgesChange={editable ? onEdgesChange : undefined}
        onConnect={handleConnect}
        onNodeClick={(_, node) => onSelectNode?.(node.id)}
        onEdgeClick={(_, edge) => onSelectEdge?.(edge.id)}
        onPaneClick={() => { onSelectNode?.(null); onSelectEdge?.(null); }}
        onNodeDragStop={() => {
          if (!editable || !onNodesMoved) return;
          const moved: Record<string, { x: number; y: number }> = {};
          for (const n of nodes) moved[n.id] = { x: Math.round(n.position.x), y: Math.round(n.position.y) };
          onNodesMoved(moved);
        }}
        onDelete={({ nodes: dn, edges: de }) => {
          if (!editable) return;
          onDeleteSelection?.(dn.map((n) => n.id), de.map((e) => e.id));
        }}
        nodesDraggable={editable}
        nodesConnectable={editable}
        elementsSelectable
        proOptions={{ hideAttribution: true }}
        // React Flow's default floor is 0.5, at which a graph taller than twice
        // the canvas simply overflows instead of fitting. A real workflow is
        // tall, so let it zoom out far enough to actually show the whole thing.
        minZoom={0.2}
        maxZoom={1.75}
        fitView
        fitViewOptions={{ padding: 0.15 }}
      >
        <Background gap={18} size={1} />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}
