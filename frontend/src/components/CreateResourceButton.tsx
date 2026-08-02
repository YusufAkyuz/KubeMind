import { Link } from 'react-router-dom'
import { useCanWrite } from '../auth/useCanWrite'
import { useKindPermission } from '../hooks/useClusterPermissions'
import { IconPlus } from './Icons'

interface Props {
  clusterId?: string
  ns?: string
  kind: string
}

/**
 * "+ Create" entry point shown on resource list pages — ADMIN, or a USER on
 * a cluster they registered themselves (see useCanWrite).
 *
 * When the cluster says this kubeconfig can't create the kind here, the button
 * stays visible but disabled with the reason: hiding it would leave the user
 * wondering where it went, and letting them click it only to meet a 403 is
 * worse still.
 */
export function CreateResourceButton({ clusterId, ns, kind }: Props) {
  const canWrite = useCanWrite(clusterId, kind)
  const permission = useKindPermission(clusterId, ns, kind, 'create')

  if (!canWrite || !clusterId || !ns || ns === '_' || ns === 'all') return null

  if (!permission.allowed) {
    return (
      <span
        title={permission.reason ?? undefined}
        className="inline-flex items-center gap-1.5 rounded-lg bg-gray-200 dark:bg-neutral-800 px-3 py-2 text-sm
                   font-medium text-gray-400 dark:text-neutral-500 cursor-not-allowed shrink-0"
      >
        <IconPlus className="w-4 h-4" />
        Create
      </span>
    )
  }

  return (
    <Link
      to={`/clusters/${clusterId}/namespaces/${ns}/create?kind=${encodeURIComponent(kind)}`}
      className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-3 py-2 text-sm font-medium
                 text-white hover:bg-blue-700 transition-colors shrink-0"
    >
      <IconPlus className="w-4 h-4" />
      Create
    </Link>
  )
}
