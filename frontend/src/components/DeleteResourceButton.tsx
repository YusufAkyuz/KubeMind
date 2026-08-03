import { useState } from 'react'
import { api, apiErrorMessage } from '../api/client'
import { useToast } from './Toast'
import { ConfirmDialog } from './ConfirmDialog'
import { usePrivilegedFeatures } from '../hooks/useAppConfig'
import { useKindPermission } from '../hooks/useClusterPermissions'
import { isRbacKind } from '../utils/resourceKinds'

interface Props {
  clusterId: string
  ns: string
  kind: string
  name: string
  onDeleted?: () => void
}

/** Self-contained "Delete" button + confirm dialog, generic across every EDITABLE_KINDS resource kind. */
export function DeleteResourceButton({ clusterId, ns, kind, name, onDeleted }: Props) {
  const [open, setOpen] = useState(false)
  const toast = useToast()
  const privilegedFeatures = usePrivilegedFeatures()
  const permission = useKindPermission(clusterId, ns, kind, 'delete')

  const handleDelete = async () => {
    try {
      await api.delete(`/clusters/${clusterId}/namespaces/${ns}/resources/${kind}/${name}`)
      toast.success(`${kind} ${name} deleted`)
      onDeleted?.()
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Delete failed'))
    }
  }

  // Deployments without cluster-admin refuse RBAC writes server-side — don't
  // offer a button whose only outcome would be a 403.
  if (isRbacKind(kind) && !privilegedFeatures) return null

  // The feature exists here, this kubeconfig just isn't allowed to use it —
  // say so instead of letting the click fail.
  if (!permission.allowed) {
    return (
      <button
        disabled
        title={permission.reason ?? undefined}
        className="rounded-md border border-gray-200 dark:border-neutral-700 px-3 py-1.5 text-xs font-medium
                   text-gray-400 dark:text-neutral-600 cursor-not-allowed"
      >
        Delete
      </button>
    )
  }

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="rounded-md border border-red-200 dark:border-red-500/30 px-3 py-1.5 text-xs font-medium
                   text-red-600 dark:text-red-400 hover:bg-red-50 transition-colors"
      >
        Delete
      </button>
      <ConfirmDialog
        open={open}
        title={`Delete ${kind} ${name}`}
        message={
          <>
            This will permanently delete this {kind}. This <span className="font-medium">cannot be undone</span>.
          </>
        }
        confirmLabel="Delete"
        danger
        requireText={name}
        onConfirm={handleDelete}
        onClose={() => setOpen(false)}
      />
    </>
  )
}
