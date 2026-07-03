import { useEffect, useState } from 'react'
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
  Logo,
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
  const urlNs = nsMatch ? nsMatch[1] : null

  // Remember the last namespace per cluster so leaving a namespaced page
  // (e.g. via Nodes) doesn't force re-selecting it on the way back.
  const storageKey = `kubemind.ns.${clusterId}`
  const [storedNs, setStoredNs] = useState<string | null>(() => localStorage.getItem(storageKey))

  useEffect(() => {
    setStoredNs(localStorage.getItem(storageKey))
  }, [storageKey])

  useEffect(() => {
    if (urlNs && urlNs !== '_') {
      localStorage.setItem(storageKey, urlNs)
      setStoredNs(urlNs)
    }
  }, [urlNs, storageKey])

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

  // Drop a remembered namespace that no longer exists on this cluster.
  const remembered = storedNs && namespaces && !namespaces.some((n) => n.name === storedNs)
    ? null
    : storedNs
  const currentNs = (urlNs && urlNs !== '_' ? urlNs : null) ?? remembered

  const currentCluster = clusters?.find((c) => String(c.id) === clusterId)

  const handleClusterChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    navigate(`/clusters/${e.target.value}/nodes`)
    onClose?.()
  }

  const handleNsChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const ns = e.target.value
    if (!ns) return
    navigate(urlNs
      ? location.pathname.replace(`/namespaces/${urlNs}/`, `/namespaces/${ns}/`)
      : `/clusters/${clusterId}/namespaces/${ns}/pods`)
    onClose?.()
  }

  const navLinkClass = ({ isActive }: { isActive: boolean }) =>
    [
      'group flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors',
      isActive
        ? 'bg-blue-500/15 text-blue-300 font-medium'
        : 'text-slate-400 hover:bg-slate-800/70 hover:text-slate-100',
    ].join(' ')

  const selectClass =
    'w-full text-sm border border-slate-700 rounded-lg px-2.5 py-1.5 bg-slate-800 text-slate-200 ' +
    'focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent'

  const sectionLabel = 'px-3 pb-1.5 text-[10px] font-semibold text-slate-500 uppercase tracking-widest'

  return (
    <aside className="flex flex-col h-full w-64 bg-slate-900 select-none">
      {/* Brand */}
      <div className="flex items-center gap-2.5 px-5 h-14 border-b border-slate-800 shrink-0">
        <Logo className="w-7 h-7" />
        <span className="text-[15px] font-semibold tracking-tight text-white">KubeMind</span>
      </div>

      {/* Cluster switcher */}
      <div className="px-4 py-3 border-b border-slate-800 shrink-0">
        <label className="block text-[10px] font-semibold text-slate-500 uppercase tracking-widest mb-1.5">
          Cluster
        </label>
        <div className="flex items-center gap-2">
          <select value={clusterId} onChange={handleClusterChange} className={selectClass}>
            {(clusters ?? [{ id: 0, name: 'local', builtIn: true } as Cluster]).map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
          {currentCluster && !currentCluster.builtIn && (
            <span
              title={currentCluster.lastCheckOk === false ? 'Unreachable at last check' : 'Healthy at last check'}
              className={`shrink-0 w-2 h-2 rounded-full ${
                currentCluster.lastCheckOk === false ? 'bg-red-500'
                : currentCluster.lastCheckOk === true ? 'bg-emerald-500' : 'bg-slate-600'
              }`}
            />
          )}
        </div>
      </div>

      {/* Namespace selector */}
      <div className="px-4 py-3 border-b border-slate-800 shrink-0">
        <label className="block text-[10px] font-semibold text-slate-500 uppercase tracking-widest mb-1.5">
          Namespace
        </label>
        <select value={currentNs ?? ''} onChange={handleNsChange} className={selectClass}>
          <option value="">Select namespace…</option>
          {namespaces?.map((ns) => (
            <option key={ns.name} value={ns.name}>{ns.name}</option>
          ))}
        </select>
      </div>

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto px-3 py-4 space-y-0.5">
        <p className={sectionLabel}>Cluster</p>
        <NavLink to={`/clusters/${clusterId}/nodes`} className={navLinkClass} onClick={onClose} end>
          <IconServer className="w-4 h-4 shrink-0" />
          Nodes
        </NavLink>
        <NavLink to={`/clusters/${clusterId}/namespaces`} className={navLinkClass} onClick={onClose} end>
          <IconFolder className="w-4 h-4 shrink-0" />
          Namespaces
        </NavLink>

        <p className={`${sectionLabel} pt-5`}>Workloads</p>
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
            <p className={`${sectionLabel} pt-5`}>Admin</p>
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
      <div className="shrink-0 px-4 py-3 border-t border-slate-800">
        <p className="text-sm font-medium text-slate-200 truncate">{username}</p>
        <button
          onClick={() => { logout(); onClose?.() }}
          className="mt-0.5 text-xs text-slate-500 hover:text-slate-300 transition-colors"
        >
          Sign out
        </button>
      </div>
    </aside>
  )
}
