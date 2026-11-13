import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Webhook } from 'lucide-react'
import { Dialog } from 'radix-ui'
import { api, ApiError, errorText } from '../api'

type Props = {
  workflowId: string
  revision: number
  dirty: boolean
  disabled: boolean
  open: boolean
  onOpenChange: (open: boolean) => void
  sessionExpired: () => void
}

export function WebhookDialog({ workflowId, revision, dirty, disabled, open, onOpenChange, sessionExpired }: Props) {
  const client = useQueryClient()
  const [token, setToken] = useState<string | null>(null)
  const queryKey = ['webhook', workflowId]
  const webhook = useQuery({ queryKey, queryFn: () => api.webhook(workflowId), enabled: open })
  const enable = useMutation({
    gcTime: 0,
    mutationFn: () => api.registerWebhook(workflowId, revision),
    onSuccess: ({ path, revision, token }) => {
      client.setQueryData(queryKey, { path, revision })
      setToken(token)
      enable.reset()
    },
  })
  const revoke = useMutation({
    mutationFn: () => api.revokeWebhook(workflowId),
    onSuccess: () => {
      setToken(null)
      void client.invalidateQueries({ queryKey })
    },
  })
  const error = enable.error ?? revoke.error ?? webhook.error
  const expired = error instanceof ApiError && error.status === 401
  useEffect(() => { if (expired) sessionExpired() }, [expired, sessionExpired])

  const busy = enable.isPending || revoke.isPending
  const registered = webhook.data?.revision != null
  const active = registered && webhook.data?.revision === revision
  const url = webhook.data ? new URL(webhook.data.path, window.location.origin).href : ''

  return <Dialog.Root open={open} onOpenChange={next => {
    if (busy) return
    if (!next) {
      setToken(null)
      enable.reset()
      revoke.reset()
    }
    onOpenChange(next)
  }}>
    <Dialog.Trigger asChild><button className="button primary" disabled={disabled}><Webhook size={15} /> Webhook</button></Dialog.Trigger>
    <Dialog.Portal>
      <Dialog.Overlay className="overlay" />
      <Dialog.Content className="dialog">
        <Dialog.Title>Webhook trigger</Dialog.Title>
        <Dialog.Description>Send a JSON object in an HTTP POST with an Authorization bearer token.</Dialog.Description>
        {webhook.isPending && <p role="status">Loading webhook…</p>}
        {webhook.data && <div className="webhook-details">
          <label>Webhook URL<input value={url} readOnly onFocus={event => event.target.select()} /></label>
          <p>{active ? `Enabled for revision ${revision}.` : registered
            ? 'The workflow revision changed. Enable the webhook again for the current revision.' : 'Webhook disabled.'}</p>
          {dirty && <p>Save your changes before enabling or rotating the token.</p>}
          {token && <label>Bearer token<input className="json-input" value={token} readOnly
            onFocus={event => event.target.select()} />
            <small>Copy this token before closing. Rotating it invalidates the previous token.</small>
          </label>}
        </div>}
        {error && <p className="notice error" role="alert">{errorText(error)}</p>}
        <div className="dialog-actions">
          <Dialog.Close asChild><button className="button" disabled={busy}>Close</button></Dialog.Close>
          {webhook.isError && <button className="button" onClick={() => void webhook.refetch()}>Retry</button>}
          {registered && <button className="button" disabled={busy || webhook.isFetching} onClick={() => { enable.reset(); revoke.mutate() }}>Disable</button>}
          {webhook.data && <button className="button primary" disabled={busy || dirty || webhook.isFetching}
            onClick={() => { revoke.reset(); enable.mutate() }}>{active ? 'Rotate token' : 'Enable webhook'}</button>}
        </div>
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>
}
