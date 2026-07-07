import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { IconPlus } from './Icons'

interface Props {
  clusterId?: string
  ns?: string
  kind: string
}

/** "+ Create" entry point shown on resource list pages, ADMIN-only. */
export function CreateResourceButton({ clusterId, ns, kind }: Props) {
  const { isAdmin } = useAuth()
  if (!isAdmin || !clusterId || !ns || ns === '_' || ns === 'all') return null

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
