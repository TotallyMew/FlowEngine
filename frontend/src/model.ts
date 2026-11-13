import type { Edge, Node } from '@xyflow/react'

export type Json = null | boolean | number | string | Json[] | JsonObject
export type JsonObject = { [key: string]: Json }

export type NodeKind = 'MANUAL_TRIGGER' | 'WEBHOOK_TRIGGER' | 'SET_DATA' | 'LOG' | 'CONDITION' | 'TRANSFORM_DATA'
  | 'HTTP_REQUEST' | 'GITHUB_CREATE_ISSUE'
export type OutputPort = 'out' | 'true' | 'false'

export const isTrigger = (kind: NodeKind) => kind === 'MANUAL_TRIGGER' || kind === 'WEBHOOK_TRIGGER'
export type Position = { x: number; y: number }
export type NodeConfig = Record<string, Json>

export type GraphNode = { id: string; type: NodeKind; label: string; position: Position; config: NodeConfig }
export type GraphEdge = { id: string; source: string; target: string; sourcePort?: OutputPort }
export type Graph = { nodes: GraphNode[]; edges: GraphEdge[] }

export type NodeField = { key: string; label: string; kind: 'text' | 'json' | 'select'; description: string }
export type NodeDefinition = {
  type: NodeKind
  label: string
  description: string
  input: boolean
  output: boolean
  outputPorts: OutputPort[]
  fields: NodeField[]
}

export type DraftSummary = { id: string; name: string; revision: number; updatedAt: string }
export type Draft = DraftSummary & { graph: Graph }

export type RunStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type NodeStatus = 'COMPLETED' | 'FAILED' | 'SKIPPED'
export type RunSummary = {
  id: string
  workflowRevision: number
  status: RunStatus
  requestedAt: string
  startedAt: string | null
  finishedAt: string | null
}
export type NodeResult = {
  nodeId: string
  status: NodeStatus
  startedAt: string | null
  finishedAt: string | null
  input: JsonObject | null
  output: JsonObject | null
  message: string | null
  error: string | null
  outputPort?: OutputPort | null
}
export type Run = RunSummary & { workflowId: string; graph: Graph; input: JsonObject; results: NodeResult[]; error: string | null }

export type CanvasNode = Node<{
  kind: NodeKind
  label: string
  config: NodeConfig
  status?: NodeStatus
}, 'workflow'>
export type CanvasEdge = Edge

export const activeRun = (status: RunStatus | undefined) => status === 'PENDING' || status === 'RUNNING'

export const lastExecutedNode = (run: Run | undefined) => run?.results.findLast(node => node.status !== 'SKIPPED')
