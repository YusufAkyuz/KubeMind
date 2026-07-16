import { useState } from 'react'
import { api, apiErrorMessage } from '../api/client'
import { useToast } from './Toast'
import { ConfirmDialog } from './ConfirmDialog'

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

  const handleDelete = async () => {
    try {
      await api.delete(`/clusters/${clusterId}/namespaces/${ns}/resources/${kind}/${name}`)
      toast.success(`${kind} ${name} deleted`)
      onDeleted?.()
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Delete failed'))
    }
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
