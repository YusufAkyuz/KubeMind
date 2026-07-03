import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import {
  IconServer,
  IconFolder,
  IconCube,
  IconLayers,
  IconClipboard,
  IconHelm,
} from './Icons'
import type { Cluster, Namespace } from '../types/k8s'

const WORKLOAD_NAV = [
  { suffix: 'pods', label: 'Pods', Icon: IconCube },
  { suffix: 'deployments', label: 'Deployments', Icon: IconLayers },
  { suffix: 'events', label: 'Events', Icon: IconClipboard },
]

interface Props {
  onClose?: () => void
}

export function Sidebar({ onClose }: Props) {
  const { username, isAdmin, logout } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()

  // Derive current cluster and namespace from the URL
  const clusterMatch = location.pathname.match(/^\/clusters\/(\d+)/)
  const clusterId = clusterMatch ? clusterMatch[1] : '0'
  const nsMatch = location.pathname.match(/^\/clusters\/\d+\/namespaces\/([^/]+)\//)
  const currentNs = nsMatch ? nsMatch[1] : null

  const { data: clusters } = useQuery<Cluster[]>({
    queryKey: ['clusters'],
    queryFn: async () => (await api.get<Cluster[]>('/clusters')).data,
    staleTime: 30_000,
  })

  const { data: namespaces } = useQuery<Namespace[]>({
    queryKey: ['namespaces', clusterId],
    queryFn: async () => (await api.get<Namespace[]>(`/clusters/${clusterId}/namespaces`)).data,
    staleTime: 30_000,
  })

  const currentCluster = clusters?.find((c) => String(c.id) === clusterId)

  const handleClusterChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const id = e.target.value
    // Namespaces differ between clusters — land on nodes, the safe cluster-wide page.
    navigate(`/clusters/${id}/nodes`)
    onClose?.()
  }

  const handleNsChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const ns = e.target.value
    if (!ns) return
    navigate(currentNs
      ? location.pathname.replace(`/namespaces/${currentNs}/`, `/namespaces/${ns}/`)
      : `/clusters/${clusterId}/namespaces/${ns}/pods`)
    onClose?.()
  }

  const navLinkClass = ({ isActive }: { isActive: boolean }) =>
    [
      'group flex items-center gap-3 px-3 py-2 rounded-md text-sm transition-colors',
      isActive
        ? 'bg-blue-50 text-blue-700 font-medium'
        : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900',
    ].join(' ')

  return (
    <aside className="flex flex-col h-full w-64 bg-white border-r border-gray-200 select-none">
      {/* Brand */}
      <div className="flex items-center gap-2.5 px-5 h-14 border-b border-gray-200 shrink-0">
        <IconHelm className="w-5 h-5 text-blue-600" />
        <span className="text-[15px] font-semibold tracking-tight text-gray-900">KubeMind</span>
      </div>

      {/* Cluster switcher */}
      <div className="px-4 py-3 border-b border-gray-200 shrink-0">
        <label className="block text-[10px] font-semibold text-gray-400 uppercase tracking-widest mb-1.5">
          Cluster
        </label>
        <div className="flex items-center gap-2">
          <select
            value={clusterId}
            onChange={handleClusterChange}
            className="w-full text-sm border border-gray-300 rounded-md px-2.5 py-1.5 bg-white text-gray-700
                       focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          >
            {(clusters ?? [{ id: 0, name: 'local', builtIn: true } as Cluster]).map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
          {currentCluster && !currentCluster.builtIn && (
            <span
              title={currentCluster.lastCheckOk === false ? 'Unreachable at last check' : 'Healthy at last check'}
              className={`shrink-0 w-2 h-2 rounded-full ${
                currentCluster.lastCheckOk === false ? 'bg-red-500'
                : currentCluster.lastCheckOk === true ? 'bg-emerald-500' : 'bg-gray-300'
              }`}
            />
          )}
        </div>
      </div>

      {/* Namespace selector */}
      <div className="px-4 py-3 border-b border-gray-200 shrink-0">
        <label className="block text-[10px] font-semibold text-gray-400 uppercase tracking-widest mb-1.5">
          Namespace
        </label>
        <select
          value={currentNs ?? ''}
          onChange={handleNsChange}
          className="w-full text-sm border border-gray-300 rounded-md px-2.5 py-1.5 bg-white text-gray-700
                     focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
        >
          <option value="">Select namespace…</option>
          {namespaces?.map((ns) => (
            <option key={ns.name} value={ns.name}>{ns.name}</option>
          ))}
        </select>
      </div>

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto px-3 py-3 space-y-0.5">
        <p className="px-3 pb-1.5 text-[10px] font-semibold text-gray-400 uppercase tracking-widest">
          Cluster
        </p>
        <NavLink to={`/clusters/${clusterId}/nodes`} className={navLinkClass} onClick={onClose} end>
          <IconServer className="w-4 h-4 shrink-0" />
          Nodes
        </NavLink>
        <NavLink to={`/clusters/${clusterId}/namespaces`} className={navLinkClass} onClick={onClose} end>
          <IconFolder className="w-4 h-4 shrink-0" />
          Namespaces
        </NavLink>

        <p className="px-3 pt-4 pb-1.5 text-[10px] font-semibold text-gray-400 uppercase tracking-widest">
          Workloads
        </p>
        {WORKLOAD_NAV.map(({ suffix, label, Icon }) => (
          <NavLink
            key={suffix}
            to={`/clusters/${clusterId}/namespaces/${currentNs ?? '_'}/${suffix}`}
            className={navLinkClass}
            onClick={onClose}
          >
            <Icon className="w-4 h-4 shrink-0" />
            {label}
          </NavLink>
        ))}

        {isAdmin && (
          <>
            <p className="px-3 pt-4 pb-1.5 text-[10px] font-semibold text-gray-400 uppercase tracking-widest">
              Admin
            </p>
            <NavLink to="/settings/clusters" className={navLinkClass} onClick={onClose}>
              <IconServer className="w-4 h-4 shrink-0" />
              Clusters
            </NavLink>
            <NavLink to="/audit" className={navLinkClass} onClick={onClose}>
              <IconClipboard className="w-4 h-4 shrink-0" />
              Audit log
            </NavLink>
          </>
        )}
      </nav>

      {/* User footer */}
      <div className="shrink-0 px-4 py-3 border-t border-gray-200">
        <p className="text-sm font-medium text-gray-700 truncate">{username}</p>
        <button
          onClick={() => { logout(); onClose?.() }}
          className="mt-0.5 text-xs text-gray-400 hover:text-gray-600 transition-colors"
        >
          Sign out
        </button>
      </div>
    </aside>
  )
}
