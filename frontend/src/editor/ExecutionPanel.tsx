import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronDown, ChevronLeft, ChevronRight, ChevronUp, Clock3, ListTree, Terminal } from 'lucide-react'
import { api, errorText } from '../api'
import { activeRun, lastExecutedNode } from '../model'
import type { Run, RunStatus } from '../model'
import { isBranchPort } from './graph'

export function Status({ status }: { status: RunStatus | 'SKIPPED' }) {
  return <span className={`run-status ${status.toLowerCase()}`}><span className="dot" />{
    status.charAt(0) + status.slice(1).toLowerCase()
  }</span>
}

const time = (value: string) => new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })

function duration(start: string, end: string) {
  const ms = Date.parse(end) - Date.parse(start)
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(2)} s`
}

type Props = {
  workflowId: string
  runId: string | null
  selectedNode: string | null
  run: Run | undefined
  runError: Error | null
  retry: () => void
  selectRun: (id: string) => void
  selectNode: (id: string) => void
  open: boolean
  toggle: () => void
}

export function ExecutionPanel({ workflowId, runId, selectedNode, run, runError, retry, selectRun, selectNode, open, toggle }: Props) {
  const [page, setPage] = useState(0)
  const history = useQuery({
    queryKey: ['executions', workflowId, page],
    queryFn: () => api.history(workflowId, page),
    refetchInterval: query => query.state.data?.some(item => activeRun(item.status)) || activeRun(run?.status) ? 1500 : false,
    enabled: open,
  })
  const result = run?.results.find(node => node.nodeId === selectedNode) ?? lastExecutedNode(run)
  const label = (id: string) => run?.graph.nodes.find(node => node.id === id)?.label ?? id

  return <section className={`execution-panel ${open ? 'expanded' : ''}`} aria-label="Executions">
    <button className="execution-toggle" onClick={toggle} aria-expanded={open} aria-controls="execution-content">
      <Terminal size={16} /><span>Executions</span>{run && <Status status={run.status} />}
      {open ? <ChevronDown size={16} className="toggle-icon" /> : <ChevronUp size={16} className="toggle-icon" />}
    </button>
    {open && <div className="execution-content" id="execution-content">
      <div className="run-history">
        <div className="history-heading"><h3><Clock3 size={14} /> Run history</h3>
          <div className="pagination">
            <button className="icon-button" aria-label="Newer runs" disabled={page === 0} onClick={() => setPage(page - 1)}><ChevronLeft size={14} /></button>
            <button className="icon-button" aria-label="Older runs" disabled={history.data?.length !== 20} onClick={() => setPage(page + 1)}><ChevronRight size={14} /></button>
          </div>
        </div>
        {history.isPending && <p className="muted">Loading runs…</p>}
        {history.error && <><p className="notice error" role="alert">{errorText(history.error)}</p><button className="button" onClick={() => void history.refetch()}>Retry history</button></>}
        {history.data?.length === 0 && <p className="muted">{page ? 'No older runs.' : 'No executions yet.'}</p>}
        {history.data?.map(item => <button key={item.id} aria-pressed={item.id === runId} className="history-row" onClick={() => selectRun(item.id)}>
          <span>#{item.id.slice(0, 8)}<small>{new Date(item.requestedAt).toLocaleDateString()} · {time(item.requestedAt)}</small></span>
          <Status status={run?.id === item.id ? run.status : item.status} />
        </button>)}
      </div>
      <div className="run-details">
        {runError ? <><p className="notice error" role="alert">{errorText(runError)}</p><button className="button" onClick={retry}>Retry execution details</button></>
          : !run ? <div className="panel-empty"><ListTree size={20} /><p>{runId ? 'Loading execution…' : 'Select a run to inspect its nodes and output.'}</p></div>
          : <>
            <div className="run-detail-heading">
              <span title={run.id}>Run #{run.id.slice(0, 8)} <span className="muted">· revision {run.workflowRevision}</span></span>
              <span className="muted">{run.startedAt && `Started ${time(run.startedAt)}`}{run.startedAt && run.finishedAt && ` · Finished ${time(run.finishedAt)} · ${duration(run.startedAt, run.finishedAt)}`}</span>
            </div>
            {run.error && <p className="notice error" role="alert">{run.error}</p>}
            {activeRun(run.status) && <p role="status" className="muted">{run.status === 'PENDING' ? 'Waiting for a worker…' : 'Executing workflow…'}</p>}
            {!run.results.length && run.input && <div className="payloads"><div><h4>Run input</h4>
              <pre>{JSON.stringify(run.input, null, 2)}</pre></div></div>}
            {!!run.results.length && <div className="result-grid">
              <div className="result-nodes" aria-label="Node results">
                {run.results.map(node => <button key={node.nodeId} className="result-row" onClick={() => selectNode(node.nodeId)}
                  aria-pressed={node.nodeId === result?.nodeId}><span>{label(node.nodeId)}</span><Status status={node.status} /></button>)}
              </div>
              {result && <div className="result-data">
                <h3>{label(result.nodeId)}{result.startedAt && result.finishedAt && <small>{duration(result.startedAt, result.finishedAt)}</small>}</h3>
                {isBranchPort(result.outputPort) && <p>Selected output <span className={`branch-tag branch-${result.outputPort}`}>{result.outputPort}</span></p>}
                {result.message && <p className="log-message">{result.message}</p>}
                {result.error && <p className="notice error">{result.error}</p>}
                <div className="payloads"><div><h4>Input</h4><pre>{result.input ? JSON.stringify(result.input, null, 2) : '—'}</pre></div>
                  <div><h4>Output</h4><pre>{result.output ? JSON.stringify(result.output, null, 2) : '—'}</pre></div></div>
              </div>}
            </div>}
          </>}
      </div>
    </div>}
  </section>
}
