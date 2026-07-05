import { useState } from 'react'
import { api, apiErrorMessage } from '../api/client'
import { Modal } from './Modal'
import { useToast } from './Toast'

interface Props {
  /** Full POST path, e.g. `/clusters/0/namespaces/ns/deployments/x/scale`. */
  endpoint: string
  resourceName: string
  currentReplicas: number
  readyReplicas: number
  onScaled?: () => void
}

/** Self-contained "Scale" button + modal, reusable across Deployment/StatefulSet. */
export function ScaleButton({ endpoint, resourceName, currentReplicas, readyReplicas, onScaled }: Props) {
  const [open, setOpen] = useState(false)
  const [replicas, setReplicas] = useState(currentReplicas)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()

  const openModal = () => {
    setReplicas(currentReplicas)
    setError(null)
    setOpen(true)
  }

  const scale = async () => {
    setBusy(true)
    setError(null)
    try {
      await api.post(endpoint, { replicas })
      toast.success(`Scaled ${resourceName} to ${replicas} replica${replicas === 1 ? '' : 's'}`)
      setOpen(false)
      onScaled?.()
    } catch (e) {
      setError(apiErrorMessage(e, 'Scale failed'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <button
        onClick={openModal}
        className="rounded-md border border-gray-300 px-3 py-1.5 text-xs font-medium text-gray-700
                   hover:bg-gray-50 transition-colors"
      >
        Scale
      </button>

      <Modal open={open} title={`Scale ${resourceName}`} onClose={() => setOpen(false)}>
        <div className="space-y-4">
          <p className="text-sm text-gray-600">
            Currently <span className="font-medium">{readyReplicas}/{currentReplicas}</span> replicas ready.
          </p>
          <div>
            <label className="block text-xs text-gray-500 mb-1.5">Desired replicas</label>
            <input
              type="number"
              min={0}
              max={500}
              value={replicas}
              onChange={(e) => setReplicas(Math.max(0, Math.min(500, Number(e.target.value) || 0)))}
              className="w-28 rounded-md border border-gray-300 px-3 py-2 text-sm tabular-nums
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
          </div>
          {replicas === 0 && (
            <p className="text-xs text-amber-600">Scaling to 0 stops all its pods.</p>
          )}
          {error && <p className="text-sm text-red-600">{error}</p>}
          <div className="flex justify-end gap-2">
            <button
              onClick={() => setOpen(false)}
              disabled={busy}
              className="rounded-md border border-gray-300 px-3.5 py-2 text-sm text-gray-700
                         hover:bg-gray-50 disabled:opacity-50 transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={scale}
              disabled={busy || replicas === currentReplicas}
              className="rounded-md bg-blue-600 px-3.5 py-2 text-sm font-medium text-white
                         hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {busy ? 'Scaling…' : 'Scale'}
            </button>
          </div>
        </div>
      </Modal>
    </>
  )
}
