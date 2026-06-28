import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import type { Namespace } from '../types/k8s'

interface Props {
  onClose?: () => void
}

const NAV_ITEMS = [
  { to: '/nodes', label: 'Nodes', icon: '🖥' },
  { to: '/namespaces', label: 'Namespaces', icon: '📁' },
]

const NS_NAV_ITEMS = [
  { suffix: 'pods', label: 'Pods', icon: '📦' },
  { suffix: 'deployments', label: 'Deployments', icon: '🚀' },
  { suffix: 'events', label: 'Events', icon: '📋' },
]

function linkClass({ isActive }: { isActive: boolean }) {
  return `flex items-center gap-2 px-3 py-2 rounded-md text-sm font-medium transition-colors ${
    isActive
      ? 'bg-blue-50 text-blue-700'
      : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
  }`
}

export function Sidebar({ onClose }: Props) {
  const { username, logout } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()

  const { data: namespaces } = useQuery<Namespace[]>({
    queryKey: ['namespaces'],
    queryFn: async () => (await api.get<Namespace[]>('/k8s/namespaces')).data,
    staleTime: 30_000,
  })

  // Derive current namespace from the URL, e.g. /namespaces/default/pods → "default"
  const nsMatch = location.pathname.match(/^\/namespaces\/([^/]+)/)
  const currentNs = nsMatch ? nsMatch[1] : null

  const handleNsChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const ns = e.target.value
    if (!ns) return
    if (currentNs) {
      navigate(location.pathname.replace(`/namespaces/${currentNs}/`, `/namespaces/${ns}/`))
    } else {
      navigate(`/namespaces/${ns}/pods`)
    }
    onClose?.()
  }

  return (
    <div className="flex flex-col h-full bg-white border-r w-64">
      {/* Logo */}
      <div className="flex items-center gap-2 px-4 py-5 border-b">
        <span className="text-blue-600 text-xl">⎈</span>
        <span className="text-lg font-bold text-gray-800">KubeMind</span>
      </div>

      {/* Namespace selector */}
      <div className="px-3 py-3 border-b">
        <label className="block text-xs font-medium text-gray-500 uppercase tracking-wide mb-1">
          Namespace
        </label>
        <select
          value={currentNs ?? ''}
          onChange={handleNsChange}
          className="w-full text-sm border border-gray-300 rounded-md px-2 py-1.5 bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
          <option value="">— select —</option>
          {namespaces?.map((ns) => (
            <option key={ns.name} value={ns.name}>
              {ns.name}
            </option>
          ))}
        </select>
      </div>

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto px-2 py-3 space-y-0.5">
        <p className="px-3 py-1 text-xs font-semibold text-gray-400 uppercase tracking-wider">Cluster</p>
        {NAV_ITEMS.map((item) => (
          <NavLink key={item.to} to={item.to} className={linkClass} onClick={onClose}>
            <span>{item.icon}</span>
            {item.label}
          </NavLink>
        ))}

        <p className="px-3 pt-4 pb-1 text-xs font-semibold text-gray-400 uppercase tracking-wider">Workloads</p>
        {NS_NAV_ITEMS.map((item) => {
          const ns = currentNs ?? '_'
          const to = `/namespaces/${ns}/${item.suffix}`
          return (
            <NavLink key={item.suffix} to={to} className={linkClass} onClick={onClose}>
              <span>{item.icon}</span>
              {item.label}
            </NavLink>
          )
        })}
      </nav>

      {/* Footer */}
      <div className="px-4 py-4 border-t text-sm text-gray-600">
        <div className="font-medium truncate">{username}</div>
        <button
          onClick={() => { logout(); onClose?.() }}
          className="mt-1 text-blue-600 hover:underline text-xs"
        >
          Sign out
        </button>
      </div>
    </div>
  )
}
