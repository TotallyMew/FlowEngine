import { useEffect, useState } from 'react'
import { ReactFlowProvider } from '@xyflow/react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Copy, Ellipsis, Play, RotateCcw, Save, Trash2 } from 'lucide-react'
import { AlertDialog, DropdownMenu } from 'radix-ui'
import { useStore } from 'zustand'
import { api, ApiError, errorText } from '../api'
import { activeRun } from '../model'
import type { Draft, NodeDefinition } from '../model'
import { Canvas } from './Canvas'
import { ExecutionPanel } from './ExecutionPanel'
import { configProblem, serializeGraph } from './graph'
import { Inspector } from './Inspector'
import { createEditor, isDirty } from './store'

type Props = {
  draft: Draft
  definitions: NodeDefinition[]
  onDirty: (dirty: boolean) => void
  selectWorkflow: (id: string | null) => void
  sessionExpired: () => void
}

export function Editor({ draft, definitions, onDirty, selectWorkflow, sessionExpired }: Props) {
  const client = useQueryClient()
  const [store] = useState(() => createEditor(draft))
  const name = useStore(store, state => state.name)
  const revision = useStore(store, state => state.revision)
  const dirty = useStore(store, isDirty)
  const [showRuns, setShowRuns] = useState(false)
  const [runId, setRunId] = useState<string | null>(null)
  const [resultNode, setResultNode] = useState<string | null>(null)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const [draftLoad, setDraftLoad] = useState(0)

  useEffect(() => onDirty(dirty), [dirty, onDirty])

  const run = useQuery({
    queryKey: ['execution', draft.id, runId],
    queryFn: () => api.result(draft.id, runId!),
    enabled: !!runId,
    refetchInterval: query => activeRun(query.state.data?.status) ? 600 : false,
  })

  const runStatus = run.data?.status
  useEffect(() => {
    if (runStatus && !activeRun(runStatus)) void client.invalidateQueries({ queryKey: ['executions', draft.id] })
  }, [client, draft.id, runStatus])

  const save = useMutation({
    mutationFn: ({ name, nodes, edges, revision }: ReturnType<typeof store.getState>) =>
      api.save(draft.id, name.trim(), serializeGraph(nodes, edges), revision),
    onSuccess: saved => {
      store.getState().saved(saved)
      client.setQueryData(['workflow', saved.id], saved)
      void client.invalidateQueries({ queryKey: ['workflows'] })
    },
  })

  function openRun(id: string) {
    setRunId(id)
    setResultNode(null)
    setShowRuns(true)
  }

  const execute = useMutation({
    mutationFn: () => api.run(draft.id, store.getState().revision),
    onSuccess: queued => {
      client.setQueryData(['execution', draft.id, queued.id], queued)
      void client.invalidateQueries({ queryKey: ['executions', draft.id] })
      openRun(queued.id)
    },
  })

  const duplicate = useMutation({
    mutationFn: () => api.duplicate(draft.id),
    onSuccess: copy => {
      client.setQueryData(['workflow', copy.id], copy)
      void client.invalidateQueries({ queryKey: ['workflows'] })
      selectWorkflow(copy.id)
    },
  })

  const remove = useMutation({
    mutationFn: () => api.remove(draft.id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['workflows'] })
      selectWorkflow(null)
    },
  })

  const reload = useMutation({
    mutationFn: () => api.get(draft.id),
    onSuccess: updated => {
      client.setQueryData(['workflow', draft.id], updated)
      store.getState().replace(updated)
      setDraftLoad(load => load + 1)
      save.reset()
      execute.reset()
      setFormError(null)
    },
  })

  const error = save.error ?? execute.error ?? duplicate.error ?? remove.error ?? reload.error
  const expired = [error, run.error].some(item => item instanceof ApiError && item.status === 401)
  useEffect(() => { if (expired) sessionExpired() }, [expired, sessionExpired])

  function clearErrors() {
    save.reset()
    execute.reset()
    duplicate.reset()
    reload.reset()
    remove.reset()
    setFormError(null)
  }

  const busy = save.isPending || execute.isPending || duplicate.isPending || remove.isPending || reload.isPending

  const submitSave = () => {
    if (busy || runId || !isDirty(store.getState())) return
    clearErrors()
    const state = store.getState()
    const invalid = state.nodes.find(node => !node.data.label.trim() || state.configText[node.id] !== undefined
      || configProblem(node.data.kind, node.data.config))
    if (!state.name.trim()) {
      setFormError('Enter a workflow name.')
    } else if (invalid) {
      store.getState().select(invalid.id)
      setFormError('Review this node’s name and configuration before saving.')
    } else {
      save.mutate(state)
    }
  }

  useEffect(() => {
    function shortcut(event: KeyboardEvent) {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault()
        submitSave()
      }
    }
    window.addEventListener('keydown', shortcut)
    return () => window.removeEventListener('keydown', shortcut)
  })

  return <main className="editor">
    <div className="editor-toolbar">
      <div className="editor-title">
        {runId ? <button className="button" onClick={() => setRunId(null)}><ArrowLeft size={15} /> Back to editor</button>
          : <input className="workflow-name" aria-label="Workflow name" value={name} maxLength={100}
            onChange={event => store.getState().rename(event.target.value)} />}
        {runId ? <span className="snapshot-name">{name}</span> : <>
          <span className="draft-tag">Draft</span>
          <span className={`save-indicator ${dirty ? 'unsaved' : ''}`}><span className="dot" />{dirty ? 'Unsaved changes' : `Saved · r${revision}`}</span>
        </>}
      </div>
      {!runId && <div className="toolbar-actions">
        <button className="button" title="Save (Ctrl+S)" disabled={busy || !dirty || !!runId} onClick={submitSave}><Save size={15} /> {save.isPending ? 'Saving…' : 'Save'}</button>
        <button className="button primary" title={dirty ? 'Save changes before running' : 'Run the saved workflow'}
          disabled={busy || dirty || !!runId} onClick={() => { clearErrors(); execute.mutate() }}><Play size={15} /> {execute.isPending ? 'Queueing…' : 'Run workflow'}</button>
        <DropdownMenu.Root>
          <DropdownMenu.Trigger asChild><button className="icon-button menu-trigger" aria-label="Workflow actions" disabled={busy || !!runId}><Ellipsis size={18} /></button></DropdownMenu.Trigger>
          <DropdownMenu.Portal><DropdownMenu.Content className="menu" align="end" sideOffset={6}>
            <DropdownMenu.Item className="menu-item" disabled={dirty} onSelect={() => { clearErrors(); duplicate.mutate() }}><Copy size={15} /> Duplicate workflow</DropdownMenu.Item>
            <DropdownMenu.Item className="menu-item" onSelect={() => {
              if (!dirty || window.confirm('Discard unsaved changes and reload this workflow?')) { clearErrors(); reload.mutate() }
            }}><RotateCcw size={15} /> Reload saved draft</DropdownMenu.Item>
            <DropdownMenu.Item className="menu-item danger" onSelect={() => { clearErrors(); setDeleteOpen(true) }}><Trash2 size={15} /> Delete workflow</DropdownMenu.Item>
          </DropdownMenu.Content></DropdownMenu.Portal>
        </DropdownMenu.Root>
      </div>}
    </div>
    {(formError || (error && !deleteOpen)) && <div className="editor-error notice error" role="alert">{formError ?? errorText(error)}</div>}
    <div className={`editor-grid ${runId ? 'snapshot-mode' : ''}`}>
      <ReactFlowProvider key={runId ?? `draft-${draftLoad}`}>
        {runId && !run.data ? <div className="snapshot-loading">{run.error ? 'Execution snapshot unavailable.' : 'Loading execution snapshot…'}</div>
          : <Canvas store={store} definitions={definitions} run={run.data} selectResult={setResultNode} resultNode={resultNode ?? run.data?.results.at(-1)?.nodeId ?? null} />}
      </ReactFlowProvider>
      {!runId && <Inspector key={draftLoad} store={store} definitions={definitions} />}
    </div>
    <ExecutionPanel workflowId={draft.id} runId={runId} selectedNode={resultNode} run={run.data} runError={run.error}
      retry={() => void run.refetch()} selectRun={openRun} selectNode={setResultNode} open={showRuns} toggle={() => setShowRuns(!showRuns)} />
    <AlertDialog.Root open={deleteOpen} onOpenChange={open => { if (!remove.isPending) setDeleteOpen(open) }}>
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="overlay" />
        <AlertDialog.Content className="dialog">
          <AlertDialog.Title>Delete workflow?</AlertDialog.Title>
          <AlertDialog.Description>This permanently deletes the draft and all of its execution history.</AlertDialog.Description>
          {remove.error && <p className="notice error" role="alert">{errorText(remove.error)}</p>}
          <div className="dialog-actions">
            <AlertDialog.Cancel asChild><button className="button" disabled={remove.isPending}>Cancel</button></AlertDialog.Cancel>
            <AlertDialog.Action asChild><button className="button destructive" disabled={remove.isPending}
              onClick={event => { event.preventDefault(); remove.mutate() }}>Delete workflow</button></AlertDialog.Action>
          </div>
        </AlertDialog.Content>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  </main>
}
