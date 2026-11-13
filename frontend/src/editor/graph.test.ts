import { describe, expect, it } from 'vitest'
import type { Draft, Graph, NodeDefinition } from '../model'
import { configProblem, connectionProblem, loadGraph, parseValues, serializeGraph } from './graph'
import { createEditor, isDirty } from './store'

const graph: Graph = {
  nodes: [
    { id: 'start', type: 'MANUAL_TRIGGER', label: 'Start', position: { x: 24, y: 9 }, config: {} },
    { id: 'data', type: 'SET_DATA', label: 'Payload', position: { x: 200, y: 50 }, config: { values: { count: 3 } } },
    { id: 'log', type: 'LOG', label: 'Log result', position: { x: 450, y: 70 }, config: { message: 'Saved' } },
  ],
  edges: [{ id: 'e1', source: 'start', target: 'data' }],
}

const draft: Draft = { id: 'workflow', revision: 1, name: 'Run example', updatedAt: '2026-10-01T10:00:00Z', graph }
const trigger: NodeDefinition = {
  type: 'MANUAL_TRIGGER', label: 'Manual Trigger', description: '', input: false, output: true, outputPorts: ['out'], fields: [],
}

it('round-trips both condition outputs and preserves a legacy connection', () => {
  const conditional: Graph = {
    nodes: [...graph.nodes, { id: 'check', type: 'CONDITION', label: 'Check', position: { x: 0, y: 0 },
      config: { field: 'sku', operator: 'IS_EMPTY' } }],
    edges: [{ id: 'a', source: 'start', target: 'check' },
      { id: 'b', source: 'check', target: 'data', sourcePort: 'true' },
      { id: 'c', source: 'check', target: 'log', sourcePort: 'false' }],
  }
  const { nodes, edges } = loadGraph(conditional)
  expect(edges.map(edge => edge.sourceHandle)).toEqual(['out', 'true', 'false'])
  expect(serializeGraph(nodes, edges)).toEqual(conditional)
  expect(connectionProblem({ source: 'check', target: 'data' }, nodes, [])).toContain('true or false')
  expect(configProblem('CONDITION', { field: '', operator: 'IS_EMPTY' })).toContain('field name')
})

describe('graph serialization', () => {
  it('preserves positions, config, and edges while dropping canvas UI state', () => {
    const { nodes, edges } = loadGraph(graph)
    nodes[0]!.selected = true
    nodes[0]!.data.status = 'COMPLETED'
    edges[0]!.selected = true
    expect(serializeGraph(nodes, edges)).toEqual(graph)
  })
})

describe('node configuration', () => {
  it('preserves nested JSON and rejects non-objects or malformed JSON', () => {
    expect(parseValues('{"list":[null,3],"ok":true}')).toEqual({ list: [null, 3], ok: true })
    expect(() => parseValues('[]')).toThrow('JSON object')
    expect(() => parseValues('{"value":')).toThrow('valid JSON')
    expect(() => parseValues(JSON.stringify(Object.fromEntries(Array.from({ length: 101 }, (_, i) => [i, i]))))).toThrow('100')
    expect(configProblem('LOG', { message: '   ' })).toContain('message')
    expect(configProblem('LOG', { message: 'Recorded' })).toBeUndefined()
  })
})

describe('editor interactions', () => {
  it('rejects unsupported connections without changing the graph', () => {
    const store = createEditor(draft)
    const { nodes, edges } = store.getState()
    expect(connectionProblem({ source: 'log', target: 'start' }, nodes, edges)).toContain('no input')
    expect(connectionProblem({ source: 'log', target: 'data' }, nodes, edges)).toContain('incoming')
    store.getState().connect('data', 'log')
    expect(store.getState().edges).toHaveLength(2)
    store.getState().remove('start')
    store.getState().connect('log', 'data')
    expect(store.getState().feedback).toContain('cycle')
    expect(store.getState().edges).toHaveLength(1)
  })

  it('tracks editor edits, removes incident edges, and prevents duplicate triggers', () => {
    const store = createEditor(draft)
    store.getState().select('data')
    expect(isDirty(store.getState())).toBe(false)
    store.getState().add(trigger, { x: 0, y: 0 })
    expect(store.getState().feedback).toContain('one trigger')
    store.getState().duplicate('data')
    expect(store.getState().nodes).toHaveLength(4)
    expect(isDirty(store.getState())).toBe(true)
    store.getState().remove('data')
    expect(store.getState().edges).toHaveLength(0)
    store.getState().replace(draft)
    expect(isDirty(store.getState())).toBe(false)
    store.getState().changeNodes([{ type: 'position', id: 'data', position: { x: 225, y: 40 } }])
    expect(isDirty(store.getState())).toBe(true)
  })

  it('retains edits made while a save is in flight', () => {
    const store = createEditor(draft)
    store.getState().rename('First')
    store.getState().rename('Second')
    store.getState().saved({ ...draft, name: 'First', revision: 2 })
    expect(store.getState().name).toBe('Second')
    expect(store.getState().revision).toBe(2)
    expect(isDirty(store.getState())).toBe(true)
  })

  it('treats a reordered JSONB response as saved', () => {
    const store = createEditor(draft)
    store.getState().updateNode('data', { config: { values: { priority: 2, id: 'FE-42' } } })
    const saved = serializeGraph(store.getState().nodes, store.getState().edges)
    saved.nodes[1]!.config = { values: { id: 'FE-42', priority: 2 } }
    store.getState().saved({ ...draft, graph: saved, revision: 2 })
    expect(isDirty(store.getState())).toBe(false)
  })
})
