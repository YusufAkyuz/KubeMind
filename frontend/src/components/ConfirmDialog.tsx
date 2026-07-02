import { useState } from 'react'
import type { ReactNode } from 'react'
import { Modal } from './Modal'

interface Props {
  open: boolean
  title: string
  message: ReactNode
  confirmLabel: string
  danger?: boolean
  /** When set, the user must type this exact string to enable the confirm button. */
  requireText?: string
  onConfirm: () => Promise<void>
  onClose: () => void
}

export function ConfirmDialog({
  open, title, message, confirmLabel, danger, requireText, onConfirm, onClose,
}: Props) {
  const [typed, setTyped] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const canConfirm = !busy && (!requireText || typed === requireText)

  const handleConfirm = async () => {
    setBusy(true)
    setError(null)
    try {
      await onConfirm()
      setTyped('')
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Action failed')
    } finally {
      setBusy(false)
    }
  }

  const handleClose = () => {
    if (busy) return
    setTyped('')
    setError(null)
    onClose()
  }

  return (
    <Modal open={open} title={title} onClose={handleClose}>
      <div className="space-y-4">
        <div className="text-sm text-gray-600 leading-relaxed">{message}</div>

        {requireText && (
          <div>
            <label className="block text-xs text-gray-500 mb-1.5">
              Type <span className="font-mono font-medium text-gray-800">{requireText}</span> to confirm
            </label>
            <input
              type="text"
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              autoComplete="off"
              spellCheck={false}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm font-mono
                         focus:outline-none focus:ring-2 focus:ring-red-500 focus:border-transparent"
            />
          </div>
        )}

        {error && <p className="text-sm text-red-600">{error}</p>}

        <div className="flex justify-end gap-2 pt-1">
          <button
            onClick={handleClose}
            disabled={busy}
            className="rounded-md border border-gray-300 px-3.5 py-2 text-sm text-gray-700
                       hover:bg-gray-50 disabled:opacity-50 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleConfirm}
            disabled={!canConfirm}
            className={`rounded-md px-3.5 py-2 text-sm font-medium text-white transition-colors
                        disabled:opacity-50 disabled:cursor-not-allowed
                        ${danger ? 'bg-red-600 hover:bg-red-700' : 'bg-blue-600 hover:bg-blue-700'}`}
          >
            {busy ? 'Working…' : confirmLabel}
          </button>
        </div>
      </div>
    </Modal>
  )
}
