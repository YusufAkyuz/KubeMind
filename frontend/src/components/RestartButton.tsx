import { api, apiErrorMessage } from '../api/client'
import { ConfirmDialog } from './ConfirmDialog'
import { useToast } from './Toast'
import { useState } from 'react'

interface Props {
  /** Full POST path, e.g. `/clusters/0/namespaces/ns/daemonsets/x/restart`. */
  endpoint: string
  resourceName: string
  onRestarted?: () => void
}

/** Self-contained "Restart" button + confirm dialog, reusable across Deployment/StatefulSet/DaemonSet. */
export function RestartButton({ endpoint, resourceName, onRestarted }: Props) {
  const [open, setOpen] = useState(false)
  const toast = useToast()

  const restart = async () => {
    try {
      await api.post(endpoint)
      toast.success(`Rollout restart triggered for ${resourceName}`)
      onRestarted?.()
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Restart failed'))
    }
  }

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="rounded-md border border-gray-300 px-3 py-1.5 text-xs font-medium text-gray-700
                   hover:bg-gray-50 transition-colors"
      >
        Restart
      </button>
      <ConfirmDialog
        open={open}
        title={`Restart ${resourceName}`}
        message={
          <>
            This triggers a <span className="font-medium">rolling restart</span>: pods are replaced
            one by one. No downtime is expected if replicas &gt; 1 and probes are configured.
          </>
        }
        confirmLabel="Restart"
        onConfirm={restart}
        onClose={() => setOpen(false)}
      />
    </>
  )
}
