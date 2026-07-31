import { Link } from 'react-router-dom'
import { useCanWrite } from '../auth/useCanWrite'
import { IconPlus } from './Icons'

interface Props {
  clusterId?: string
  kind: string
}

/** "+ Create" entry point for cluster-scoped kinds (ClusterRole/ClusterRoleBinding) —
 *  ADMIN, or a USER on a cluster they registered themselves. See
 *  CreateResourceButton for the namespaced equivalent. */
export function CreateClusterResourceButton({ clusterId, kind }: Props) {
  const canWrite = useCanWrite(clusterId, kind)
  if (!canWrite || !clusterId) return null

  return (
    <Link
      to={`/clusters/${clusterId}/create?kind=${encodeURIComponent(kind)}`}
      className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-3 py-2 text-sm font-medium
                 text-white hover:bg-blue-700 transition-colors shrink-0"
    >
      <IconPlus className="w-4 h-4" />
      Create
    </Link>
  )
}
