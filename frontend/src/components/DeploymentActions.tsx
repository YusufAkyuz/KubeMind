import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api, apiErrorMessage } from '../api/client'
import { Modal } from './Modal'
import { ConfirmDialog } from './ConfirmDialog'
import { useToast } from './Toast'
import type { Deployment } from '../types/k8s'

interface Props {
  namespace: string
  deployment: Deployment
  onActionDone?: () => void
}

type Dialog = 'scale' | 'restart' | 'yaml' | null

export function DeploymentActions({ namespace, deployment, onActionDone }: Props) {
  const [dialog, setDialog] = useState<Dialog>(null)
  const [replicas, setReplicas] = useState(deployment.desiredReplicas)
  const [yaml, setYaml] = useState('')
  const [yamlLoading, setYamlLoading] = useState(false)
  const [yamlError, setYamlError] = useState<string | null>(null)
  const [applying, setApplying] = useState(false)
  const toast = useToast()
  const queryClient = useQueryClient()

  const base = `/k8s/namespaces/${namespace}/deployments/${deployment.name}`

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['deployments', namespace] })
    onActionDone?.()
  }

  const doScale = async () => {
    try {
      await api.post(`${base}/scale`, { replicas })
      toast.success(`Scaled ${deployment.name} to ${replicas} replica${replicas === 1 ? '' : 's'}`)
      refresh()
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Scale failed'))
    }
  }

  const doRestart = async () => {
    try {
      await api.post(`${base}/restart`)
      toast.success(`Rollout restart triggered for ${deployment.name}`)
      refresh()
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Restart failed'))
    }
  }

  const openYamlEditor = async () => {
    setDialog('yaml')
    setYamlLoading(true)
    setYamlError(null)
    try {
      const res = await api.get<string>(`${base}/yaml`, {
        responseType: 'text',
        transformResponse: [(data) => data], // keep raw YAML string, skip JSON parse
      })
      setYaml(res.data)
    } catch (e) {
      setYamlError(apiErrorMessage(e, 'Could not load YAML'))
    } finally {
      setYamlLoading(false)
    }
  }

  const applyYaml = async () => {
    setApplying(true)
    setYamlError(null)
    try {
      await api.put(`${base}/yaml`, yaml, {
        headers: { 'Content-Type': 'application/yaml' },
      })
      toast.success(`Applied YAML for ${deployment.name}`)
      setDialog(null)
      refresh()
    } catch (e) {
      setYamlError(apiErrorMessage(e, 'Apply failed'))
    } finally {
      setApplying(false)
    }
  }

  const actionButton =
    'rounded-md border border-gray-300 px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50 transition-colors'

  return (
    <>
      <div className="flex flex-wrap gap-2">
        <button className={actionButton} onClick={() => { setReplicas(deployment.desiredReplicas); setDialog('scale') }}>
          Scale
        </button>
        <button className={actionButton} onClick={() => setDialog('restart')}>
          Restart
        </button>
        <button className={actionButton} onClick={openYamlEditor}>
          Edit YAML
        </button>
      </div>

      {/* ── Scale dialog ─────────────────────────────────────────────────── */}
      <Modal open={dialog === 'scale'} title={`Scale ${deployment.name}`} onClose={() => setDialog(null)}>
        <div className="space-y-4">
          <p className="text-sm text-gray-600">
            Currently <span className="font-medium">{deployment.readyReplicas}/{deployment.desiredReplicas}</span> replicas ready.
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
            <p className="text-xs text-amber-600">
              Scaling to 0 stops all pods of this deployment.
            </p>
          )}
          <ScaleConfirmRow
            disabled={replicas === deployment.desiredReplicas}
            onCancel={() => setDialog(null)}
            onConfirm={async () => { await doScale(); setDialog(null) }}
          />
        </div>
      </Modal>

      {/* ── Restart confirm ──────────────────────────────────────────────── */}
      <ConfirmDialog
        open={dialog === 'restart'}
        title={`Restart ${deployment.name}`}
        message={
          <>
            This triggers a <span className="font-medium">rolling restart</span>: pods are replaced
            one by one following the deployment's update strategy. No downtime is expected if
            replicas &gt; 1 and probes are configured.
          </>
        }
        confirmLabel="Restart"
        onConfirm={doRestart}
        onClose={() => setDialog(null)}
      />

      {/* ── YAML editor ──────────────────────────────────────────────────── */}
      <Modal open={dialog === 'yaml'} title={`Edit YAML — ${deployment.name}`} onClose={() => setDialog(null)} wide>
        <div className="space-y-3">
          {yamlLoading && <p className="text-sm text-gray-400">Loading manifest…</p>}
          {!yamlLoading && (
            <textarea
              value={yaml}
              onChange={(e) => setYaml(e.target.value)}
              spellCheck={false}
              className="w-full h-[50vh] rounded-md border border-gray-300 bg-gray-950 text-gray-100
                         font-mono text-xs leading-5 p-3 resize-none
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
          )}
          {yamlError && <p className="text-sm text-red-600">{yamlError}</p>}
          <div className="flex items-center justify-between">
            <p className="text-xs text-gray-400">
              name / namespace cannot be changed; conflicts return an error — reload and retry.
            </p>
            <div className="flex gap-2">
              <button
                onClick={() => setDialog(null)}
                disabled={applying}
                className="rounded-md border border-gray-300 px-3.5 py-2 text-sm text-gray-700
                           hover:bg-gray-50 disabled:opacity-50 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={applyYaml}
                disabled={applying || yamlLoading || !yaml.trim()}
                className="rounded-md bg-blue-600 px-3.5 py-2 text-sm font-medium text-white
                           hover:bg-blue-700 disabled:opacity-50 transition-colors"
              >
                {applying ? 'Applying…' : 'Apply'}
              </button>
            </div>
          </div>
        </div>
      </Modal>
    </>
  )
}

function ScaleConfirmRow({ disabled, onCancel, onConfirm }: {
  disabled: boolean
  onCancel: () => void
  onConfirm: () => Promise<void>
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handle = async () => {
    setBusy(true)
    setError(null)
    try {
      await onConfirm()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Scale failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-2">
      {error && <p className="text-sm text-red-600">{error}</p>}
      <div className="flex justify-end gap-2">
        <button
          onClick={onCancel}
          disabled={busy}
          className="rounded-md border border-gray-300 px-3.5 py-2 text-sm text-gray-700
                     hover:bg-gray-50 disabled:opacity-50 transition-colors"
        >
          Cancel
        </button>
        <button
          onClick={handle}
          disabled={disabled || busy}
          className="rounded-md bg-blue-600 px-3.5 py-2 text-sm font-medium text-white
                     hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {busy ? 'Scaling…' : 'Scale'}
        </button>
      </div>
    </div>
  )
}
