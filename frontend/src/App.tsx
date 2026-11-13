import { Activity, ArrowRight, GitFork } from 'lucide-react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { lazy, Suspense, useCallback } from 'react'
import type { FormEvent } from 'react'
import { api, ApiError, errorText } from './api'
import { Brand } from './Brand'

const Workspace = lazy(() => import('./Workspace').then(module => ({ default: module.Workspace })))

export function App() {
  const client = useQueryClient()
  const session = useQuery({ queryKey: ['session'], queryFn: api.session })
  const health = useQuery({ queryKey: ['health'], queryFn: api.health, refetchInterval: 15_000 })
  const sessionExpired = useCallback(() => {
    client.setQueryData(['session'], null)
    client.removeQueries({ predicate: query => !['health', 'session'].includes(String(query.queryKey[0])) })
    void client.invalidateQueries({ queryKey: ['session'] })
  }, [client])
  const login = useMutation({
    mutationFn: (form: FormData) => api.login(String(form.get('username')), String(form.get('password'))),
    onSuccess: () => client.invalidateQueries({ queryKey: ['session'] }),
  })
  const logout = useMutation({
    mutationFn: api.logout,
    onSuccess: () => {
      sessionExpired()
      login.reset()
    },
  })

  if (session.data) {
    return <Suspense fallback={<main className="sign-in" role="status">Loading workspace…</main>}>
      <Workspace username={session.data.username} healthy={health.data?.status === 'UP' && !health.isError}
        signOut={() => logout.mutate()} sessionExpired={sessionExpired} signOutError={logout.error} />
    </Suspense>
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    login.mutate(new FormData(event.currentTarget))
  }

  const unavailable = session.isError && !(session.error instanceof ApiError && session.error.status === 401)
  return <main className="sign-in">
    <div className="sign-in-inner">
      <Brand />
      <div className="sign-in-card">
        <GitFork className="login-icon" size={25} />
        <h1>Sign in to your workspace</h1>
        {session.isPending ? <p className="muted" role="status">Checking session…</p> : <form onSubmit={submit}>
          <label>Username<input name="username" autoComplete="username" required maxLength={80} /></label>
          <label>Password<input name="password" type="password" autoComplete="current-password" required /></label>
          {login.error && <p className="notice error" role="alert">{errorText(login.error)}</p>}
          {unavailable && <p className="notice error" role="alert">Backend unavailable. Check that FlowEngine is running.</p>}
          <button className="button primary login-button" disabled={login.isPending}>
            {login.isPending ? 'Signing in…' : 'Sign in'}<ArrowRight size={16} />
          </button>
        </form>}
      </div>
      <span className="login-health"><Activity size={14} />{health.data?.status === 'UP' && !health.isError ? 'Backend online' : 'Backend unavailable'}</span>
    </div>
  </main>
}
