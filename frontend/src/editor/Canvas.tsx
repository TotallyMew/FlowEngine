import {
  Background, Controls, Handle, MiniMap, Position, ReactFlow, useReactFlow,
} from '@xyflow/react'
import type { NodeProps } from '@xyflow/react'
import { Check, Code2, GitFork, Minus, Plus, Shuffle, SquareTerminal, Webhook, X, Zap } from 'lucide-react'
import { useRef, useState } from 'react'
import { useStore } from 'zustand'
import { isTrigger } from '../model'
import type { CanvasEdge, CanvasNode, NodeDefinition, NodeKind, Run } from '../model'
import { connectionProblem, isBranchPort, loadGraph, outputPorts } from './graph'
import type { createEditor } from './store'

export const nodePresentation = {
  MANUAL_TRIGGER: { icon: Zap, color: 'trigger', category: 'Trigger' },
  WEBHOOK_TRIGGER: { icon: Webhook, color: 'trigger', category: 'Trigger' },
  SET_DATA: { icon: Code2, color: 'data', category: 'Data' },
  TRANSFORM_DATA: { icon: Shuffle, color: 'data', category: 'Data' },
  LOG: { icon: SquareTerminal, color: 'log', category: 'Utility' },
  CONDITION: { icon: GitFork, color: 'condition', category: 'Logic' },
}

function WorkflowNode({ data, selected }: NodeProps<CanvasNode>) {
  const presentation = nodePresentation[data.kind]
  const Icon = presentation.icon
  const StatusIcon = data.status === 'COMPLETED' ? Check : data.status === 'FAILED' ? X : Minus
  return <div className={`flow-node ${presentation.color} ${selected ? 'selected' : ''}`}>
    {!isTrigger(data.kind) && <Handle type="target" position={Position.Left} id="in" />}
    <span className="node-glyph"><Icon size={18} /></span>
    <div className="node-title"><strong>{data.label}</strong><span>{presentation.category}</span></div>
    {data.status && <span className={`node-result ${data.status.toLowerCase()}`} title={data.status}
      aria-label={data.status}><StatusIcon size={14} /></span>}
    {outputPorts(data.kind).map(port => isBranchPort(port) ? <Handle key={port} type="source" position={Position.Right}
      id={port} className={`branch-handle branch-${port}`} aria-label={`${port} output`} title={`${port} output`}>
      <span className="port-label">{port}</span>
    </Handle> : <Handle key={port} type="source" position={Position.Right} id={port} />)}
  </div>
}

const nodeTypes = { workflow: WorkflowNode }

type Props = {
  store: ReturnType<typeof createEditor>
  definitions: NodeDefinition[]
  run: Run | undefined
  selectResult: (id: string) => void
  resultNode: string | null
}

export function Canvas({ store, definitions, run, selectResult, resultNode }: Props) {
  const nodes = useStore(store, state => state.nodes)
  const edges = useStore(store, state => state.edges)
  const feedback = useStore(store, state => state.feedback)
  const flow = useReactFlow<CanvasNode, CanvasEdge>()
  const snapshot = run ? loadGraph(run.graph) : undefined
  const statuses = new Map(run?.results.map(node => [node.nodeId, node.status]))
  const shownNodes = snapshot?.nodes.map(node => ({
    ...node, selected: node.id === resultNode, data: { ...node.data, status: statuses.get(node.id) },
  })) ?? nodes
  const shownEdges = snapshot?.edges.map(edge => ({
    ...edge,
    className: statuses.get(edge.target) === 'SKIPPED' ? `${edge.className ?? ''} edge-skipped` : edge.className,
  })) ?? edges
  const canvasRef = useRef<HTMLElement>(null)
  const [fitOnMount] = useState(() => shownNodes.length > 0)

  function addAtCenter(definition: NodeDefinition) {
    const canvas = canvasRef.current?.getBoundingClientRect()
    if (!canvas) return
    const point = flow.screenToFlowPosition({
      x: canvas.left + canvas.width / 2 - 98 + (nodes.length % 4) * 34,
      y: canvas.top + canvas.height / 2 - 64 + (nodes.length % 4) * 76,
    })
    store.getState().add(definition, point)
  }

  return <>
    {!run && <aside className="library" aria-label="Node library">
      <h2>Node library</h2>
      <p className="muted library-hint">Click or drag a node onto the canvas.</p>
      <div className="library-items">
        {definitions.map(definition => {
          const { icon: Icon, color } = nodePresentation[definition.type]
          return <button className={`library-item ${color}`} key={definition.type} draggable
            onClick={() => addAtCenter(definition)}
            onDragStart={event => {
              event.dataTransfer.setData('application/flowengine-node', definition.type)
              event.dataTransfer.effectAllowed = 'copy'
            }}>
            <span className="node-glyph"><Icon size={17} /></span>
            <span><strong>{definition.label}</strong><small>{definition.description}</small></span>
          </button>
        })}
      </div>
    </aside>}
    <section ref={canvasRef} className="editor-canvas" aria-label={run ? 'Execution graph' : 'Workflow canvas'}
      onDragOver={event => {
        if (!run && event.dataTransfer.types.includes('application/flowengine-node')) {
          event.preventDefault()
          event.dataTransfer.dropEffect = 'copy'
        }
      }}
      onDrop={event => {
        if (run) return
        const type = event.dataTransfer.getData('application/flowengine-node') as NodeKind
        const definition = definitions.find(item => item.type === type)
        if (definition) {
          event.preventDefault()
          store.getState().add(definition, flow.screenToFlowPosition({ x: event.clientX - 90, y: event.clientY - 28 }))
        }
      }}>
      <div className="canvas-meta"><span>{run ? `Run snapshot · r${run.workflowRevision}` : 'Canvas'}</span>
        <span>{shownNodes.length} {shownNodes.length === 1 ? 'node' : 'nodes'} · {shownEdges.length} {shownEdges.length === 1 ? 'edge' : 'edges'}</span>
      </div>
      <ReactFlow<CanvasNode, CanvasEdge> key={run?.id ?? 'draft'} nodes={shownNodes} edges={shownEdges} nodeTypes={nodeTypes}
        colorMode="dark" fitView={fitOnMount} fitViewOptions={{ maxZoom: 1, padding: 0.25 }} minZoom={0.18} maxZoom={2}
        nodesDraggable={!run} nodesConnectable={!run} deleteKeyCode={run ? null : ['Backspace', 'Delete']}
        onNodesChange={run ? undefined : store.getState().changeNodes}
        onEdgesChange={run ? undefined : store.getState().changeEdges}
        onNodeClick={run ? (_, node) => selectResult(node.id) : undefined}
        onPaneClick={run ? undefined : () => store.getState().select(null)}
        isValidConnection={connection => !connectionProblem(connection, nodes, edges)}
        onConnect={connection => store.getState().connect(connection.source, connection.target, connection.sourceHandle ?? undefined)}
        onConnectEnd={(_, state) => {
          if (!state.isValid && state.fromNode && state.toNode) {
            const source = state.fromHandle?.type === 'source' ? state.fromNode.id : state.toNode.id
            const target = state.fromHandle?.type === 'source' ? state.toNode.id : state.fromNode.id
            const sourceHandle = state.fromHandle?.type === 'source' ? state.fromHandle.id : state.toHandle?.id
            store.setState({ feedback: connectionProblem({ source, target, sourceHandle }, nodes, edges) ?? 'Connect an output to an input.' })
          }
        }}>
        <Background color="#303949" gap={22} size={1.2} />
        <Controls showInteractive={false} />
        {shownNodes.length > 0 && <MiniMap<CanvasNode> pannable zoomable maskColor="#0d142280" nodeColor={node => {
          const color = nodePresentation[node.data.kind].color
          return color === 'trigger' ? '#8fa9ff' : color === 'data' ? '#5ccabc' : color === 'condition' ? '#c9a4fa' : '#e2ac6f'
        }} />}
      </ReactFlow>
      {shownNodes.length === 0 && !run && <div className="canvas-empty">
        <span className="empty-icon"><Zap size={25} /></span>
        <h2>Start with a trigger</h2>
        <p>Add a trigger from the node library, then connect a node to its output.</p>
        <button className="button" onClick={() => {
          const trigger = definitions.find(item => item.type === 'MANUAL_TRIGGER')
          if (trigger) addAtCenter(trigger)
        }}><Plus size={15} /> Add Manual Trigger</button>
      </div>}
      {feedback && !run && <div className="canvas-notice notice error" role="alert">{feedback}
        <button className="icon-button" aria-label="Dismiss validation" onClick={() => store.setState({ feedback: null })}><X size={15} /></button>
      </div>}
    </section>
  </>
}
