import { useState } from 'react'
import { Play } from 'lucide-react'
import { Dialog } from 'radix-ui'
import { errorText } from '../api'
import type { JsonObject } from '../model'
import { parseJsonObject } from './graph'

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  onRun: (input: JsonObject) => void
  pending: boolean
  error: Error | null
  disabled: boolean
  dirty: boolean
}

export function RunInputDialog({ open, onOpenChange, onRun, pending, error, disabled, dirty }: Props) {
  const [text, setText] = useState('')
  const [problem, setProblem] = useState<string | null>(null)

  return <Dialog.Root open={open} onOpenChange={next => {
    if (pending) return
    if (next) {
      setText('')
      setProblem(null)
    }
    onOpenChange(next)
  }}>
    <Dialog.Trigger asChild>
      <button className="button primary" title={dirty ? 'Save changes before running' : 'Run the saved workflow'} disabled={disabled}>
        <Play size={15} /> Run workflow
      </button>
    </Dialog.Trigger>
    <Dialog.Portal>
      <Dialog.Overlay className="overlay" />
      <Dialog.Content className="dialog">
        <Dialog.Title>Run workflow</Dialog.Title>
        <Dialog.Description>The Manual Trigger passes this JSON to the next node. Leave it blank to start with an empty object.</Dialog.Description>
        <form onSubmit={event => {
          event.preventDefault()
          if (pending) return
          let input: JsonObject
          try {
            input = parseJsonObject(text.trim() || '{}', 'Input')
          } catch (invalid) {
            setProblem(errorText(invalid))
            return
          }
          setProblem(null)
          onRun(input)
        }}>
          <label>JSON input <textarea className="json-input" rows={7} spellCheck={false}
            placeholder={'{\n  "sku": "BIKE-123"\n}'} value={text} readOnly={pending}
            aria-invalid={!!problem} aria-describedby={problem ? 'run-input-error' : undefined}
            onChange={event => { setText(event.target.value); setProblem(null) }} /></label>
          {problem && <p id="run-input-error" className="notice error" role="alert">{problem}</p>}
          {error && <p className="notice error" role="alert">{errorText(error)}</p>}
          <div className="dialog-actions">
            <Dialog.Close asChild><button type="button" className="button" disabled={pending}>Cancel</button></Dialog.Close>
            <button className="button primary" disabled={pending}><Play size={15} /> {pending ? 'Queueing…' : 'Run'}</button>
          </div>
        </form>
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>
}
