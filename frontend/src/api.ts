import type { Draft, DraftSummary, Graph, JsonObject, NodeDefinition, Run, RunSummary } from './model'

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

type Csrf = { headerName: string; token: string }
export type Session = { username: string }
export type WebhookStatus = { path: string; revision: number | null }
export type WebhookRegistration = { path: string; revision: number; token: string }

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, { ...init, credentials: 'same-origin' })
  if (!response.ok) {
    const error = await response.json().catch(() => null) as { detail?: string } | null
    throw new ApiError(response.status, error?.detail ?? `Request failed (${response.status}).`)
  }
  return (response.status === 204 ? undefined : await response.json()) as T
}

async function write<T>(method: string, path: string, body?: object | URLSearchParams): Promise<T> {
  const csrf = await request<Csrf>('/api/session/csrf')
  const headers = new Headers({ [csrf.headerName]: csrf.token })
  if (body && !(body instanceof URLSearchParams)) headers.set('Content-Type', 'application/json')
  return request<T>(path, {
    method,
    headers,
    body: body instanceof URLSearchParams ? body : body ? JSON.stringify(body) : undefined,
  })
}

const workflows = '/api/workflows'
const runPath = (id: string) => `${workflows}/${id}/executions`

export const api = {
  session: () => request<Session>('/api/session'),
  login: (username: string, password: string) =>
    write<void>('POST', '/api/session/login', new URLSearchParams({ username, password })),
  logout: () => write<void>('POST', '/api/session/logout'),
  health: () => request<{ status: string }>('/actuator/health'),
  nodeTypes: () => request<NodeDefinition[]>('/api/node-types'),
  list: () => request<DraftSummary[]>(workflows),
  get: (id: string) => request<Draft>(`${workflows}/${id}`),
  create: (name: string) => write<Draft>('POST', workflows, { name }),
  save: (id: string, name: string, graph: Graph, revision: number) =>
    write<Draft>('PUT', `${workflows}/${id}`, { name, graph, revision }),
  duplicate: (id: string) => write<Draft>('POST', `${workflows}/${id}/duplicate`),
  remove: (id: string) => write<void>('DELETE', `${workflows}/${id}`),
  run: (id: string, revision: number, input: JsonObject) => write<Run>('POST', runPath(id), { revision, input }),
  webhook: (id: string) => request<WebhookStatus>(`${workflows}/${id}/webhook`),
  registerWebhook: (id: string, revision: number) => write<WebhookRegistration>('PUT', `${workflows}/${id}/webhook`, { revision }),
  revokeWebhook: (id: string) => write<void>('DELETE', `${workflows}/${id}/webhook`),
  history: (id: string, page: number) => request<RunSummary[]>(`${runPath(id)}?page=${page}`),
  result: (id: string, runId: string) => request<Run>(`${runPath(id)}/${runId}`),
}

export const errorText = (error: unknown) => error instanceof Error ? error.message : 'Something went wrong.'
