import type { Connection } from '@xyflow/react'
import type { CanvasEdge, CanvasNode, Graph, JsonObject, NodeConfig, NodeKind } from '../model'

export function loadGraph(graph: Graph): { nodes: CanvasNode[]; edges: CanvasEdge[] } {
  return {
    nodes: graph.nodes.map(node => ({
      id: node.id, type: 'workflow', position: node.position,
      data: { kind: node.type, label: node.label, config: node.config },
    })),
    edges: graph.edges.map(edge => ({ ...edge, type: 'smoothstep', sourceHandle: 'out', targetHandle: 'in' })),
  }
}

export function serializeGraph(nodes: CanvasNode[], edges: CanvasEdge[]): Graph {
  return {
    nodes: nodes.map(({ id, position, data }) => ({ id, position, type: data.kind, label: data.label, config: data.config })),
    edges: edges.map(({ id, source, target }) => ({ id, source, target })),
  }
}

export function initialConfig(type: NodeKind): NodeConfig {
  switch (type) {
    case 'MANUAL_TRIGGER': return {}
    case 'SET_DATA': return { values: {} }
    case 'LOG': return { message: 'Workflow completed.' }
  }
}

export function parseValues(text: string): JsonObject {
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error('Enter valid JSON.')
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('Values must be a JSON object.')
  }
  if (Object.keys(parsed).length > 100) throw new Error('Use at most 100 top-level fields.')
  return parsed as JsonObject
}

export function configProblem(kind: NodeKind, config: NodeConfig): string | undefined {
  if (kind === 'LOG' && (typeof config.message !== 'string' || !config.message.trim() || config.message.length > 1000)) {
    return 'Enter a message from 1 to 1,000 characters.'
  }
  return undefined
}

export function connectionProblem(connection: Pick<Connection, 'source' | 'target'>, nodes: CanvasNode[], edges: CanvasEdge[]) {
  const { source, target } = connection
  if (source === target) return 'A node cannot connect to itself.'
  const targetNode = nodes.find(node => node.id === target)
  if (!targetNode || !nodes.some(node => node.id === source)) return 'Connect two existing nodes.'
  if (targetNode.data.kind === 'MANUAL_TRIGGER') return 'The Manual Trigger has no input.'
  if (edges.some(edge => edge.target === target)) return 'This node already has an incoming connection.'
  const ancestors = new Map(edges.map(edge => [edge.target, edge.source]))
  let ancestor: string | undefined = source
  while (ancestor) {
    if (ancestor === target) return 'This connection would create a cycle.'
    ancestor = ancestors.get(ancestor)
  }
  return undefined
}
