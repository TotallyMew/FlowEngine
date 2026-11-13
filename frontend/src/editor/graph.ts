import type { Connection } from '@xyflow/react'
import { isTrigger } from '../model'
import type { CanvasEdge, CanvasNode, Graph, JsonObject, NodeConfig, NodeKind, OutputPort } from '../model'

const conditionPorts = ['true', 'false'] as const
export const httpMethods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const

export function outputPorts(kind: NodeKind): readonly OutputPort[] {
  return kind === 'CONDITION' ? conditionPorts : ['out']
}

export function isBranchPort(port: string | null | undefined): port is 'true' | 'false' {
  return port === 'true' || port === 'false'
}

export function canvasEdge(id: string, source: string, target: string, sourcePort: string): CanvasEdge {
  return {
    id, source, target, type: 'smoothstep', sourceHandle: sourcePort, targetHandle: 'in',
    className: isBranchPort(sourcePort) ? `branch-edge branch-${sourcePort}` : undefined,
    ariaLabel: isBranchPort(sourcePort) ? `${sourcePort} branch from ${source} to ${target}` : undefined,
  }
}

export function loadGraph(graph: Graph): { nodes: CanvasNode[]; edges: CanvasEdge[] } {
  return {
    nodes: graph.nodes.map(node => ({
      id: node.id, type: 'workflow', position: node.position,
      data: { kind: node.type, label: node.label, config: node.config },
    })),
    edges: graph.edges.map(edge => canvasEdge(edge.id, edge.source, edge.target, edge.sourcePort ?? 'out')),
  }
}

export function serializeGraph(nodes: CanvasNode[], edges: CanvasEdge[]): Graph {
  return {
    nodes: nodes.map(({ id, position, data }) => ({ id, position, type: data.kind, label: data.label, config: data.config })),
    edges: edges.map(({ id, source, target, sourceHandle }) => ({
      id, source, target, ...(isBranchPort(sourceHandle) ? { sourcePort: sourceHandle } : {}),
    })),
  }
}

export function initialConfig(type: NodeKind): NodeConfig {
  switch (type) {
    case 'MANUAL_TRIGGER': return {}
    case 'WEBHOOK_TRIGGER': return {}
    case 'SET_DATA': return { values: {} }
    case 'TRANSFORM_DATA': return { mapping: {} }
    case 'HTTP_REQUEST': return { url: '', method: 'POST' }
    case 'GITHUB_CREATE_ISSUE': return { repository: '', titleField: 'title', bodyField: '' }
    case 'LOG': return { message: 'Workflow completed.' }
    case 'CONDITION': return { field: '', operator: 'IS_EMPTY' }
  }
}

export function parseJsonObject(text: string, label: string): JsonObject {
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error('Enter valid JSON.')
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error(`${label} must be a JSON object.`)
  }
  return parsed as JsonObject
}

export function parseValues(text: string): JsonObject {
  const parsed = parseJsonObject(text, 'Values')
  if (Object.keys(parsed).length > 100) throw new Error('Use at most 100 top-level fields.')
  return parsed
}

export function configProblem(kind: NodeKind, config: NodeConfig): string | undefined {
  if (kind === 'GITHUB_CREATE_ISSUE') {
    const { repository, titleField, bodyField } = config
    if (typeof repository !== 'string' || !/^[a-z0-9](?:[a-z0-9-]{0,37}[a-z0-9])?\/[a-z0-9_.-]{1,100}$/i.test(repository)
        || repository.endsWith('/.') || repository.endsWith('/..')) {
      return 'Enter a GitHub repository in owner/repository format.'
    }
    if (typeof titleField !== 'string' || !titleField.trim() || titleField.length > 100
        || typeof bodyField !== 'string' || bodyField.length > 100 || (bodyField && !bodyField.trim())) {
      return 'Use a title field and an optional description field of at most 100 characters each.'
    }
  }
  if (kind === 'HTTP_REQUEST') {
    if (!httpMethods.some(method => method === config.method)) return 'Select an HTTP method.'
    const value = config.url
    const authority = typeof value === 'string' && value.length <= 2048 && !/[\s\\#]/.test(value)
      ? /^https:\/\/([^/?]+)(?:[/?]|$)/i.exec(value)?.[1] : undefined
    const hostname = authority?.replace(/:443$/, '').toLowerCase()
    if (!hostname || !/^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{0,62}$/.test(hostname)) {
      return 'Enter an HTTPS URL with a DNS hostname and the default HTTPS port.'
    }
  }
  if (kind === 'TRANSFORM_DATA') {
    const { mapping } = config
    if (!mapping || typeof mapping !== 'object' || Array.isArray(mapping)
        || !Object.keys(mapping).length || Object.keys(mapping).length > 100) {
      return 'Provide a JSON object with 1 to 100 field mappings.'
    }
    const validKey = (value: unknown) => typeof value === 'string' && !!value.trim() && value.length <= 100
    if (Object.entries(mapping).some(([target, source]) => !validKey(target) || !validKey(source))) {
      return 'Each output and input key must be nonblank text of at most 100 characters.'
    }
  }
  if (kind === 'LOG' && (typeof config.message !== 'string' || !config.message.trim() || config.message.length > 1000)) {
    return 'Enter a message from 1 to 1,000 characters.'
  }
  if (kind === 'CONDITION') {
    if (typeof config.field !== 'string' || !config.field.trim() || config.field.length > 100) {
      return 'Enter a top-level field name of at most 100 characters.'
    }
    if (config.operator !== 'IS_EMPTY' && (config.operator !== 'EQUALS'
        || typeof config.value !== 'string' || config.value.length > 1000)) {
      return 'Select Is empty or provide a text value for Equals.'
    }
  }
  return undefined
}

export function connectionProblem(
  connection: Pick<Connection, 'source' | 'target'> & Partial<Pick<Connection, 'sourceHandle'>>,
  nodes: CanvasNode[],
  edges: CanvasEdge[],
) {
  const { source, target, sourceHandle } = connection
  if (source === target) return 'A node cannot connect to itself.'
  const sourceNode = nodes.find(node => node.id === source)
  const targetNode = nodes.find(node => node.id === target)
  if (!targetNode || !sourceNode) return 'Connect two existing nodes.'
  if (isTrigger(targetNode.data.kind)) return 'Trigger nodes have no input.'
  if (!outputPorts(sourceNode.data.kind).some(port => port === (sourceHandle ?? 'out'))) {
    return sourceNode.data.kind === 'CONDITION' ? 'Choose the true or false output.' : 'Connection uses an unavailable output.'
  }
  if (edges.some(edge => edge.target === target)) return 'This node already has an incoming connection.'
  const ancestors = new Map(edges.map(edge => [edge.target, edge.source]))
  let ancestor: string | undefined = source
  while (ancestor) {
    if (ancestor === target) return 'This connection would create a cycle.'
    ancestor = ancestors.get(ancestor)
  }
  return undefined
}
