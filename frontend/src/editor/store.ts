import { applyEdgeChanges, applyNodeChanges } from '@xyflow/react'
import type { EdgeChange, NodeChange } from '@xyflow/react'
import { createStore } from 'zustand'
import type { CanvasEdge, CanvasNode, Draft, NodeConfig, NodeDefinition, Position } from '../model'
import { connectionProblem, initialConfig, loadGraph, serializeGraph } from './graph'

type EditorState = {
  name: string
  revision: number
  baseline: string
  nodes: CanvasNode[]
  edges: CanvasEdge[]
  configText: Record<string, string>
  selected: string | null
  feedback: string | null
  rename: (name: string) => void
  select: (id: string | null) => void
  changeNodes: (changes: NodeChange<CanvasNode>[]) => void
  changeEdges: (changes: EdgeChange<CanvasEdge>[]) => void
  add: (definition: NodeDefinition, position: Position) => void
  connect: (source: string, target: string) => void
  updateNode: (id: string, data: Partial<{ label: string; config: NodeConfig }>, text?: string) => void
  duplicate: (id: string) => void
  remove: (id: string) => void
  saved: (draft: Draft) => void
  replace: (draft: Draft) => void
}

const snapshot = (name: string, nodes: CanvasNode[], edges: CanvasEdge[]) =>
  JSON.stringify({ name, graph: serializeGraph(nodes, edges) }, (_key, value: unknown) => {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return value
    // PostgreSQL JSONB can reorder object keys during a round trip.
    return Object.fromEntries(Object.entries(value).sort(([left], [right]) => left < right ? -1 : Number(left > right)))
  })

export const isDirty = (state: EditorState) =>
  state.baseline !== snapshot(state.name, state.nodes, state.edges) || Object.keys(state.configText).length > 0

const fromDraft = (draft: Draft) => {
  const { nodes, edges } = loadGraph(draft.graph)
  return {
    name: draft.name, revision: draft.revision, nodes, edges,
    baseline: snapshot(draft.name, nodes, edges), configText: {},
    selected: null, feedback: null,
  }
}

export function createEditor(draft: Draft) {
  return createStore<EditorState>((set, get) => ({
    ...fromDraft(draft),
    rename: name => set({ name }),
    select: selected => set(state => ({
      selected,
      nodes: state.nodes.map(node => ({ ...node, selected: node.id === selected })),
    })),
    changeNodes: changes => set(state => {
      const nodes = applyNodeChanges(changes, state.nodes)
      const ids = new Set(nodes.map(node => node.id))
      return {
        nodes,
        selected: nodes.find(node => node.selected)?.id ?? null,
        edges: state.edges.filter(edge => ids.has(edge.source) && ids.has(edge.target)),
        configText: Object.fromEntries(Object.entries(state.configText).filter(([id]) => ids.has(id))),
      }
    }),
    changeEdges: changes => set(state => ({ edges: applyEdgeChanges(changes, state.edges) })),
    add: (definition, position) => {
      const { nodes } = get()
      if (nodes.length >= 50 || (!definition.input && nodes.some(node => node.data.kind === definition.type))) {
        set({ feedback: nodes.length >= 50 ? 'A workflow can have up to 50 nodes.' : 'A workflow can have one Manual Trigger.' })
        return
      }
      const id = crypto.randomUUID()
      set({
        selected: id, feedback: null,
        nodes: [...nodes.map(node => ({ ...node, selected: false })), {
          id, type: 'workflow', position, selected: true,
          data: { kind: definition.type, label: definition.label, config: initialConfig(definition.type) },
        }],
      })
    },
    connect: (source, target) => set(state => {
      const feedback = connectionProblem({ source, target }, state.nodes, state.edges)
      if (feedback) return { feedback }
      return { feedback: null, edges: [...state.edges, {
        id: crypto.randomUUID(), source, target, type: 'smoothstep', sourceHandle: 'out', targetHandle: 'in',
      }] }
    }),
    updateNode: (id, data, text) => set(state => {
      const configText = { ...state.configText }
      if (text !== undefined) configText[id] = text
      else if (data.config) delete configText[id]
      return { configText, nodes: state.nodes.map(node => node.id === id ? { ...node, data: { ...node.data, ...data } } : node) }
    }),
    duplicate: id => set(state => {
      const original = state.nodes.find(node => node.id === id)
      if (!original || original.data.kind === 'MANUAL_TRIGGER' || state.nodes.length >= 50) return state
      const copyId = crypto.randomUUID()
      const text = state.configText[id]
      return {
        selected: copyId, feedback: null,
        configText: text === undefined ? state.configText : { ...state.configText, [copyId]: text },
        nodes: [...state.nodes.map(node => ({ ...node, selected: false })), {
          ...original, id: copyId, selected: true,
          position: { x: original.position.x + 32, y: original.position.y + 96 },
        }],
      }
    }),
    remove: id => get().changeNodes([{ type: 'remove', id }]),
    saved: updated => set(state => {
      const saved = fromDraft(updated)
      return { revision: saved.revision, baseline: saved.baseline, name: state.name.trim() === updated.name ? updated.name : state.name }
    }),
    replace: updated => set(fromDraft(updated)),
  }))
}
