import { useState } from 'react'
import { Copy, Link2, MousePointer2, Trash2 } from 'lucide-react'
import { useStore } from 'zustand'
import type { CanvasNode, NodeDefinition } from '../model'
import { configProblem, parseValues } from './graph'
import type { createEditor } from './store'
import { nodePresentation } from './Canvas'

type Props = {
  store: ReturnType<typeof createEditor>
  definitions: NodeDefinition[]
}

function NodeSettings({ store, node, definition }: { store: Props['store']; node: CanvasNode; definition: NodeDefinition }) {
  const { id, data } = node
  const Icon = nodePresentation[data.kind].icon
  const nodes = useStore(store, state => state.nodes)
  const edges = useStore(store, state => state.edges)
  const text = useStore(store, state => state.configText[id])
  const [values, setValues] = useState(() => text ?? JSON.stringify(data.config.values, null, 2))
  const [problem, setProblem] = useState<string | null>(null)
  const [target, setTarget] = useState('')
  const update = store.getState().updateNode
  const outgoing = edges.filter(edge => edge.source === id)
  const targets = nodes.filter(other => other.id !== id && other.data.kind !== 'MANUAL_TRIGGER')

  return <>
    <div className={`inspect-title ${nodePresentation[data.kind].color}`}>
      <span className="node-glyph"><Icon size={18} /></span>
      <div><strong>{definition.label}</strong><small>{definition.description}</small></div>
    </div>
    <label>Node name<input value={data.label} maxLength={80}
      onChange={event => update(id, { label: event.target.value })} /></label>
    {definition.fields.map(field => <label key={field.key}>{field.label}
      {field.kind === 'json'
        ? <textarea className="json-input" rows={7} spellCheck={false} aria-invalid={!!problem || text !== undefined}
            value={values} onChange={event => {
              const value = event.target.value
              setValues(value)
              try {
                update(id, { config: { [field.key]: parseValues(value) } })
                setProblem(null)
              } catch (error) {
                update(id, {}, value)
                setProblem((error as Error).message)
              }
            }} />
        : <textarea rows={3} value={String(data.config[field.key] ?? '')} maxLength={1000}
            onChange={event => update(id, { config: { [field.key]: event.target.value } })} />}
      <small>{field.description}</small>
      {(field.kind === 'json' ? problem ?? (text !== undefined ? 'Enter a valid JSON object.' : null) : configProblem(data.kind, data.config)) &&
        <span className="field-error" role="alert">{field.kind === 'json' ? problem ?? 'Enter a valid JSON object.' : configProblem(data.kind, data.config)}</span>}
    </label>)}
    <div className="inspect-section">
      <h3>Connections</h3>
      {outgoing.map(edge => <div className="connection-row" key={edge.id}>
        <span>{nodes.find(other => other.id === edge.target)?.data.label}</span>
        <button className="icon-button" aria-label={`Remove connection to ${nodes.find(other => other.id === edge.target)?.data.label}`}
          onClick={() => store.getState().changeEdges([{ type: 'remove', id: edge.id }])}><Trash2 size={14} /></button>
      </div>)}
      {targets.length ? <form className="connection-form" onSubmit={event => {
        event.preventDefault()
        if (target) store.getState().connect(id, target)
      }}>
        <select aria-label="Connection target" value={targets.some(node => node.id === target) ? target : ''}
          onChange={event => setTarget(event.target.value)}>
          <option value="">Choose a target</option>
          {targets.map(node => <option key={node.id} value={node.id}>{node.data.label}</option>)}
        </select>
        <button className="button" aria-label="Connect selected nodes" disabled={!targets.some(node => node.id === target)}><Link2 size={16} /></button>
      </form> : <p className="muted">Add another node to connect an output.</p>}
    </div>
    <div className="inspect-actions">
      {data.kind !== 'MANUAL_TRIGGER' && <button className="button" disabled={nodes.length >= 50}
        onClick={() => store.getState().duplicate(id)}><Copy size={14} /> Duplicate</button>}
      <button className="button danger" onClick={() => store.getState().remove(id)}><Trash2 size={14} /> Delete</button>
    </div>
  </>
}

export function Inspector({ store, definitions }: Props) {
  const node = useStore(store, state => state.nodes.find(item => item.id === state.selected))
  const definition = definitions.find(item => item.type === node?.data.kind)
  return <aside className="inspector" aria-label="Node configuration">
    <div className="panel-heading"><h2>Configuration</h2></div>
    {node && definition ? <div className="inspect-body">
      <NodeSettings key={node.id} store={store} node={node} definition={definition} />
    </div> : <div className="panel-empty"><MousePointer2 size={22} /><strong>Select a node</strong>
      <p>Configure its inputs and connections here.</p></div>}
  </aside>
}
