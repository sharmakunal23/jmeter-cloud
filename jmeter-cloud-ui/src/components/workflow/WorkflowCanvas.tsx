import { useEffect, useMemo } from "react";
import {
  Background, Controls, MiniMap, ReactFlow, ReactFlowProvider,
  useEdgesState, useNodesState, useReactFlow,
  type Connection, type Edge, type Node, type OnConnect,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import type { WorkflowGraph } from "../../api/workflows";
import { toFlowEdges, toFlowNodes, type FlowNodeData } from "../../lib/workflowGraph";
import { WorkflowTaskNode } from "./WorkflowTaskNode";

const nodeTypes = { workflowTask: WorkflowTaskNode };

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
  onSelectNode, onSelectEdge, onNodesMoved, onConnect, onDeleteSelection, height = 460,
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
    const t = window.setTimeout(() => fitView({ padding: 0.2, duration: 200 }), 50);
    return () => window.clearTimeout(t);
    // Deliberately only on the first render with nodes and on count changes:
    // re-framing while someone is dragging is the most annoying thing a canvas can do.
  }, [nodeCount, fitView]);

  const handleConnect: OnConnect = (connection) => {
    if (editable) onConnect?.(connection);
  };

  return (
    <div className="wfCanvas" style={{ height }}>
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
        fitView
      >
        <Background gap={18} size={1} />
        <Controls showInteractive={false} />
        {nodeCount > 6 && <MiniMap pannable zoomable />}
      </ReactFlow>
    </div>
  );
}
