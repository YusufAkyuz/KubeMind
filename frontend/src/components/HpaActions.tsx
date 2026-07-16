import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api, apiErrorMessage } from '../api/client'
import { EditYamlButton } from './EditYamlButton'
import { DeleteResourceButton } from './DeleteResourceButton'
import { Modal } from './Modal'
import { useToast } from './Toast'
import type { Hpa } from '../types/k8s'

interface Props {
  clusterId: string
  namespace: string
  hpa: Hpa
  onActionDone?: () => void
}

export function HpaActions({ clusterId, namespace, hpa, onActionDone }: Props) {
  const queryClient = useQueryClient()
  const base = `/clusters/${clusterId}/namespaces/${namespace}/hpas/${hpa.name}`

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['hpas', clusterId, namespace] })
    onActionDone?.()
  }

  return (
    <div className="flex flex-wrap gap-2">
      <ScaleHpaButton
        endpoint={`${base}/scale`}
        resourceName={hpa.name}
        currentMin={hpa.minReplicas}
        currentMax={hpa.maxReplicas}
        currentReplicas={hpa.currentReplicas}
        onScaled={refresh}
      />
      <EditYamlButton clusterId={clusterId} ns={namespace} kind="HorizontalPodAutoscaler" name={hpa.name} onApplied={refresh} />
      <DeleteResourceButton clusterId={clusterId} ns={namespace} kind="HorizontalPodAutoscaler" name={hpa.name} onDeleted={refresh} />
    </div>
  )
}

/** HPA-specific scale button — sets min/max replicas instead of a single replica count. */
function ScaleHpaButton({ endpoint, resourceName, currentMin, currentMax, currentReplicas, onScaled }: {
  endpoint: string
  resourceName: string
  currentMin: number
  currentMax: number
  currentReplicas: number
  onScaled?: () => void
}) {
  const [open, setOpen] = useState(false)
  const [minReplicas, setMinReplicas] = useState(currentMin)
  const [maxReplicas, setMaxReplicas] = useState(currentMax)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()

  const openModal = () => {
    setMinReplicas(currentMin)
    setMaxReplicas(currentMax)
    setError(null)
    setOpen(true)
  }

  const scale = async () => {
    setBusy(true)
    setError(null)
    try {
      await api.post(endpoint, { minReplicas, maxReplicas })
      toast.success(`Scaled ${resourceName} to ${minReplicas}–${maxReplicas} replicas`)
      setOpen(false)
      onScaled?.()
    } catch (e) {
      setError(apiErrorMessage(e, 'Scale failed'))
    } finally {
      setBusy(false)
    }
  }

  const unchanged = minReplicas === currentMin && maxReplicas === currentMax
  const invalid = minReplicas < 1 || maxReplicas < minReplicas

  return (
    <>
      <button
        onClick={openModal}
        className="rounded-md border border-gray-300 dark:border-slate-600 px-3 py-1.5 text-xs font-medium text-gray-700 dark:text-slate-300
                   hover:bg-gray-50 dark:hover:bg-slate-800 transition-colors"
      >
        Scale
      </button>

      <Modal open={open} title={`Scale ${resourceName}`} onClose={() => setOpen(false)}>
        <div className="space-y-4">
          <p className="text-sm text-gray-600 dark:text-slate-400">
            Currently <span className="font-medium">{currentReplicas}</span> replicas
            (range: {currentMin}–{currentMax}).
          </p>
          <div className="flex gap-4">
            <div>
              <label className="block text-xs text-gray-500 dark:text-slate-400 mb-1.5">Min replicas</label>
              <input
                type="number"
                min={1}
                max={500}
                value={minReplicas}
                onChange={(e) => setMinReplicas(Math.max(1, Math.min(500, Number(e.target.value) || 1)))}
                className="w-24 rounded-md border border-gray-300 dark:border-slate-600 px-3 py-2 text-sm tabular-nums
                           focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
              />
            </div>
            <div>
              <label className="block text-xs text-gray-500 dark:text-slate-400 mb-1.5">Max replicas</label>
              <input
                type="number"
                min={1}
                max={500}
                value={maxReplicas}
                onChange={(e) => setMaxReplicas(Math.max(1, Math.min(500, Number(e.target.value) || 1)))}
                className="w-24 rounded-md border border-gray-300 dark:border-slate-600 px-3 py-2 text-sm tabular-nums
                           focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
              />
            </div>
          </div>
          {invalid && (
            <p className="text-xs text-red-600 dark:text-red-400">Max replicas must be ≥ min replicas.</p>
          )}
          {error && <p className="text-sm text-red-600 dark:text-red-400">{error}</p>}
          <div className="flex justify-end gap-2">
            <button
              onClick={() => setOpen(false)}
              disabled={busy}
              className="rounded-md border border-gray-300 dark:border-slate-600 px-3.5 py-2 text-sm text-gray-700 dark:text-slate-300
                         hover:bg-gray-50 dark:hover:bg-slate-800 disabled:opacity-50 transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={scale}
              disabled={busy || unchanged || invalid}
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
