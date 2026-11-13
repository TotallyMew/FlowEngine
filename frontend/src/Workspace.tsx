import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Activity, ChevronRight, GitBranch, LogOut, Plus, Workflow } from 'lucide-react'
import { Dialog } from 'radix-ui'
import { Brand } from './Brand'
import { api, ApiError, errorText } from './api'
import { Editor } from './editor/Editor'

type Props = {
  username: string
  healthy: boolean
  signOut: () => void
  sessionExpired: () => void
  signOutError: Error | null
}

export function Workspace({ username, healthy, signOut, sessionExpired, signOutError }: Props) {
  const client = useQueryClient()
  const [id, setId] = useState(() => new URLSearchParams(location.search).get('workflow'))
  const [newOpen, setNewOpen] = useState(false)
  const [newName, setNewName] = useState('')
  const [dirty, setDirty] = useState(false)
  const list = useQuery({ queryKey: ['workflows'], queryFn: api.list })
  const definitions = useQuery({ queryKey: ['node-types'], queryFn: api.nodeTypes })
  const draft = useQuery({ queryKey: ['workflow', id], queryFn: () => api.get(id!), enabled: !!id })
  const errors = [list.error, definitions.error, draft.error]
  const expired = errors.some(error => error instanceof ApiError && error.status === 401)
  useEffect(() => { if (expired) sessionExpired() }, [expired, sessionExpired])

  useEffect(() => {
    function guard(event: BeforeUnloadEvent) {
      if (dirty) event.preventDefault()
    }
    window.addEventListener('beforeunload', guard)
    return () => window.removeEventListener('beforeunload', guard)
  }, [dirty])

  function select(next: string | null) {
    setDirty(false)
    setId(next)
    const url = new URL(location.href)
    if (next) url.searchParams.set('workflow', next)
    else url.searchParams.delete('workflow')
    history.replaceState(null, '', url)
  }

  const create = useMutation({
    mutationFn: api.create,
    onSuccess: created => {
      client.setQueryData(['workflow', created.id], created)
      void client.invalidateQueries({ queryKey: ['workflows'] })
      setNewOpen(false)
      select(created.id)
    },
  })

  function canLeave() {
    return !dirty || window.confirm('Discard unsaved changes to this workflow?')
  }

  function newWorkflow() {
    if (!canLeave()) return
    create.reset()
    setNewName('')
    setNewOpen(true)
  }

  return <div className="workspace">
    <header className="app-header">
      <Brand />
      <div className="breadcrumb"><span>Workspace</span><ChevronRight size={13} /><span>Workflows</span></div>
      <div className="header-right">
        <span className="health"><span className={healthy ? 'dot good' : 'dot'} />{healthy ? 'Engine online' : 'Engine unavailable'}</span>
        <span className="user-avatar" aria-hidden="true">{username[0]?.toUpperCase()}</span>
        <span className="username">{username}</span>
        <button className="icon-button" title="Sign out" aria-label="Sign out" onClick={() => { if (canLeave()) signOut() }}>
          <LogOut size={16} />
        </button>
      </div>
    </header>
    {signOutError && <p className="notice error logout-error" role="alert">{errorText(signOutError)}</p>}
    <div className="workspace-body">
      <aside className="navigation" aria-label="Workflow navigation">
        <div className="navigation-head">
          <span><GitBranch size={16} />Workflows</span>
          <button className="icon-button" aria-label="New workflow" title="New workflow" onClick={newWorkflow}><Plus size={17} /></button>
        </div>
        <nav className="workflow-list">
          {list.isPending && <p className="muted">Loading workflows…</p>}
          {list.error && <><p className="notice error" role="alert">{errorText(list.error)}</p>
            <button className="button" onClick={() => void list.refetch()}>Retry</button></>}
          {list.data?.length === 0 && <p className="muted">No workflows yet.</p>}
          {list.data?.map(workflow => <button key={workflow.id} className="workflow-link"
            aria-current={workflow.id === id ? 'page' : undefined} title={workflow.name}
            onClick={() => { if (workflow.id !== id && canLeave()) select(workflow.id) }}>
            <Workflow size={15} /><span>{workflow.name}</span>
          </button>)}
        </nav>
        <div className="navigation-footer"><Activity size={15} /><a href="/swagger-ui/index.html" target="_blank" rel="noreferrer">API reference</a></div>
      </aside>
      {id ? (
        draft.data && definitions.data
          ? <Editor key={id} draft={draft.data} definitions={definitions.data} onDirty={setDirty} selectWorkflow={select}
              sessionExpired={sessionExpired} />
          : <main className="workspace-empty">
              {draft.error || definitions.error ? <>
                <p className="notice error" role="alert">{errorText(draft.error ?? definitions.error)}</p>
                <button className="button" onClick={() => { void draft.refetch(); void definitions.refetch() }}>Retry</button>
                <button className="button" onClick={() => select(null)}>Back to workflows</button>
              </> : <p role="status">Loading workflow…</p>}
            </main>
      ) : <main className="workspace-empty">
        <div className="empty-icon"><Workflow size={26} /></div>
        <h1>{list.data?.length ? 'Open a workflow' : 'Create your first workflow'}</h1>
        <p>Connect nodes, run your workflow, and inspect the results.</p>
        <button className="button primary" onClick={newWorkflow}><Plus size={16} /> New workflow</button>
      </main>}
    </div>
    <Dialog.Root open={newOpen} onOpenChange={open => { if (!create.isPending) setNewOpen(open) }}>
      <Dialog.Portal>
        <Dialog.Overlay className="overlay" />
        <Dialog.Content className="dialog">
          <Dialog.Title>New workflow</Dialog.Title>
          <Dialog.Description>Give your workflow a name to start editing.</Dialog.Description>
          <form onSubmit={event => { event.preventDefault(); if (newName.trim()) create.mutate(newName.trim()) }}>
            <label>Workflow name<input value={newName} onChange={event => setNewName(event.target.value)} required maxLength={100} /></label>
            {create.error && <p className="notice error" role="alert">{errorText(create.error)}</p>}
            <div className="dialog-actions">
              <Dialog.Close asChild><button type="button" className="button" disabled={create.isPending}>Cancel</button></Dialog.Close>
              <button className="button primary" disabled={!newName.trim() || create.isPending}>{create.isPending ? 'Creating…' : 'Create workflow'}</button>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  </div>
}
