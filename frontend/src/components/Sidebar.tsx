import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import type { ComponentType } from 'react'
import {
  IconServer,
  IconFolder,
  IconCube,
  IconClipboard,
  IconSliders,
  IconNetwork,
  IconDatabase,
  IconChevronDown,
  IconPlus,
  IconTerminal,
  IconLayers,
  IconShield,
  IconHelm,
  Logo, IconUsers } from './Icons'
import { useTerminalPanel } from '../terminal/TerminalPanelContext'
import type { Cluster, Namespace, Pod } from '../types/k8s'

type IconType = ComponentType<{ className?: string }>

interface NavGroup {
  key: string
  label: string
  Icon: IconType
  items: { suffix: string; label: string; clusterScoped?: boolean }[]
}

// Namespaced resource groups, collapsible per category. Cluster-wide items handled separately.
const GROUPS: NavGroup[] = [
  {
    key: 'workloads', label: 'Workloads', Icon: IconCube, items: [
      { suffix: 'pods', label: 'Pods' },
      { suffix: 'deployments', label: 'Deployments' },
      { suffix: 'statefulsets', label: 'StatefulSets' },
      { suffix: 'daemonsets', label: 'DaemonSets' },
      { suffix: 'jobs', label: 'Jobs' },
      { suffix: 'cronjobs', label: 'CronJobs' },
      { suffix: 'hpas', label: 'HPAs' },
      { suffix: 'events', label: 'Events' },
    ],
  },
  {
    key: 'config', label: 'Config', Icon: IconSliders, items: [
      { suffix: 'configmaps', label: 'ConfigMaps' },
      { suffix: 'secrets', label: 'Secrets' },
    ],
  },
  {
    key: 'network', label: 'Network', Icon: IconNetwork, items: [
      { suffix: 'services', label: 'Services' },
      { suffix: 'ingresses', label: 'Ingresses' },
    ],
  },
  {
    key: 'storage', label: 'Storage', Icon: IconDatabase, items: [
      { suffix: 'persistentvolumeclaims', label: 'Persistent Volume Claims' },
      // Cluster-scoped (no namespace segment) — sits in the same group as PVCs.
      { suffix: 'persistentvolumes', label: 'Persistent Volumes', clusterScoped: true },
    ],
  },
  {
    // Create/Edit/(Delete for the namespaced ones) are wired up for every kind
    // here — see ResourceCreationService's class comment for the privilege-
    // escalation trade-off the maintainer explicitly accepted (2026-07).
    key: 'access', label: 'Access Control', Icon: IconShield, items: [
      { suffix: 'serviceaccounts', label: 'Service Accounts' },
      // ClusterRoles/ClusterRoleBindings are cluster-scoped — same pattern as PVs above.
      { suffix: 'clusterroles', label: 'Cluster Roles', clusterScoped: true },
      { suffix: 'roles', label: 'Roles' },
      { suffix: 'clusterrolebindings', label: 'Cluster Role Bindings', clusterScoped: true },
      { suffix: 'rolebindings', label: 'Role Bindings' },
    ],
  },
  {
    // Everything here shells out to the `helm` CLI on the backend (see
    // HelmCliService) — same trust tier as the Cluster Terminal.
    key: 'helm', label: 'Helm', Icon: IconHelm, items: [
      { suffix: 'helm/releases', label: 'Releases' },
      { suffix: 'helm/charts', label: 'Charts' },
    ],
  },
]

interface Props {
  onClose?: () => void
}

export function Sidebar({ onClose }: Props) {
  const { username, isAdmin, logout } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const terminalPanel = useTerminalPanel()

  const clusterMatch = location.pathname.match(/^\/clusters\/(\d+)/)
  const clusterId = clusterMatch ? clusterMatch[1] : '0'
  const nsMatch = location.pathname.match(/^\/clusters\/\d+\/namespaces\/([^/]+)\//)
  const urlNs = nsMatch ? nsMatch[1] : null

  // Remember the last namespace per cluster so leaving a namespaced page
  // doesn't force re-selecting it on the way back.
  const storageKey = `kubemind.ns.${clusterId}`
  const [storedNs, setStoredNs] = useState<string | null>(() => localStorage.getItem(storageKey))
  useEffect(() => { setStoredNs(localStorage.getItem(storageKey)) }, [storageKey])
  useEffect(() => {
    if (urlNs && urlNs !== '_') { localStorage.setItem(storageKey, urlNs); setStoredNs(urlNs) }
  }, [urlNs, storageKey])

  // Which nav group is expanded (remembered across navigations).
  const [openGroup, setOpenGroup] = useState<string>(() => localStorage.getItem('kubemind.navGroup') ?? 'workloads')
  const toggleGroup = (key: string) => {
    const next = openGroup === key ? '' : key
    setOpenGroup(next)
    localStorage.setItem('kubemind.navGroup', next)
  }

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

  const remembered = storedNs && storedNs !== 'all' && namespaces && !namespaces.some((n) => n.name === storedNs)
    ? null : storedNs
  const currentNs = (urlNs && urlNs !== '_' ? urlNs : null) ?? remembered
  const currentCluster = clusters?.find((c) => String(c.id) === clusterId)

  const { data: pods } = useQuery<Pod[]>({
    queryKey: ['pods', clusterId, currentNs ?? 'all'],
    queryFn: async () => (await api.get<Pod[]>(`/clusters/${clusterId}/namespaces/${currentNs ?? 'all'}/pods`)).data,
    enabled: !!clusterId && clusterId !== '0' && currentNs !== '_',
    refetchInterval: 15_000,
  })
  const alertPodCount = (pods ?? []).filter((p) => {
    if (p.phase === 'Succeeded' || p.phase === 'Completed') return false
    const allReady = p.containers.length === 0 || p.containers.every((c) => c.ready)
    if (p.phase === 'Running' && allReady) return false
    return p.phase === 'Failed' || p.lastTerminatedReason === 'OOMKilled' || p.containers.some((c) => !c.ready)
  }).length

  const handleClusterChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    navigate(`/clusters/${e.target.value}/nodes`); onClose?.()
  }
  const handleNsChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const ns = e.target.value
    if (!ns) return
    navigate(urlNs
      ? location.pathname.replace(`/namespaces/${urlNs}/`, `/namespaces/${ns}/`)
      : `/clusters/${clusterId}/namespaces/${ns}/pods`)
    onClose?.()
  }

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    [
      'flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors',
      isActive ? 'bg-blue-500/15 text-blue-300 font-medium'
        : 'text-slate-400 hover:bg-slate-800/70 hover:text-slate-100',
    ].join(' ')

  const subLinkClass = ({ isActive }: { isActive: boolean }) =>
    [
      'flex items-center justify-between pl-11 pr-3 py-1.5 rounded-lg text-[13px] transition-colors',
      isActive ? 'bg-blue-500/15 text-blue-300 font-medium'
        : 'text-slate-400 hover:bg-slate-800/70 hover:text-slate-100',
    ].join(' ')

  const selectClass =
    'w-full text-sm border border-slate-700 rounded-lg px-2.5 py-1.5 bg-slate-800 text-slate-200 ' +
    'focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent'
  const label = 'block text-[10px] font-semibold text-slate-500 uppercase tracking-widest mb-1.5'

  // Scrollbar only appears while actively scrolling (see .sidebar-scroll in index.css),
  // then fades back to invisible ~600ms after the last scroll event.
  const [navScrolling, setNavScrolling] = useState(false)
  const scrollTimeout = useRef<ReturnType<typeof setTimeout> | null>(null)
  const onNavScroll = () => {
    setNavScrolling(true)
    if (scrollTimeout.current) clearTimeout(scrollTimeout.current)
    scrollTimeout.current = setTimeout(() => setNavScrolling(false), 600)
  }
  useEffect(() => () => { if (scrollTimeout.current) clearTimeout(scrollTimeout.current) }, [])

  return (
    <aside className="flex flex-col h-full w-64 bg-slate-900 select-none">
      {/* Brand */}
      <div className="flex items-center gap-2.5 px-5 h-14 border-b border-slate-800 shrink-0">
        <Logo className="w-7 h-7" />
        <span className="text-[15px] font-semibold tracking-tight text-white">KubeMind</span>
      </div>

      {/* Cluster + namespace selectors */}
      <div className="px-4 py-3 border-b border-slate-800 shrink-0 space-y-3">
        <div>
          <label className={label}>Cluster</label>
          <div className="flex items-center gap-2">
            <select value={clusterId} onChange={handleClusterChange} className={selectClass}>
              {(clusters ?? [{ id: 0, name: 'local', builtIn: true } as Cluster]).map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
            {currentCluster && !currentCluster.builtIn && (
              <span
                title={currentCluster.lastCheckOk === false ? 'Unreachable' : 'Healthy'}
                className={`shrink-0 w-2 h-2 rounded-full ${currentCluster.lastCheckOk === false ? 'bg-red-500'
                  : currentCluster.lastCheckOk === true ? 'bg-emerald-500' : 'bg-slate-600'}`}
              />
            )}
          </div>
        </div>
        <div>
          <label className={label}>Namespace</label>
          <select value={currentNs ?? ''} onChange={handleNsChange} className={selectClass}>
            {/* Only shown while nothing is selected yet — once a namespace is picked,
                re-showing this placeholder in the list read as a second, redundant option. */}
            {!currentNs && <option value="">Select namespace…</option>}
            <option value="all">All namespaces</option>
            {/* A real namespace literally named "all" would otherwise collide with our
                "All namespaces" sentinel value above and show up as a visually duplicate entry. */}
            {namespaces?.filter((ns) => ns.name !== 'all').map((ns) => (
              <option key={ns.name} value={ns.name}>{ns.name}</option>
            ))}
          </select>
        </div>
        {isAdmin && currentNs && currentNs !== 'all' && (
          <Link
            to={`/clusters/${clusterId}/namespaces/${currentNs}/create`}
            onClick={onClose}
            className="flex items-center justify-center gap-1.5 w-full rounded-lg
                       bg-gradient-to-br from-blue-600 to-violet-600 px-3 py-2 text-sm font-medium
                       text-white hover:opacity-90 transition-opacity"
          >
            <IconPlus className="w-4 h-4" />
            Create resource
          </Link>
        )}
      </div>

      {/* Navigation */}
      <nav
        onScroll={onNavScroll}
        className={`sidebar-scroll flex-1 overflow-y-auto px-3 py-4 space-y-0.5 ${navScrolling ? 'is-scrolling' : ''}`}
      >
        {/* Cluster-wide */}
        <NavLink to={`/clusters/${clusterId}/nodes`} className={linkClass} onClick={onClose} end>
          <IconServer className="w-4 h-4 shrink-0" /> Nodes
        </NavLink>
        <NavLink to={`/clusters/${clusterId}/namespaces`} className={linkClass} onClick={onClose} end>
          <IconFolder className="w-4 h-4 shrink-0" /> Namespaces
        </NavLink>

        {/* Namespaced groups */}
        {GROUPS.map(({ key, label: groupLabel, Icon, items }) => {
          const expanded = openGroup === key
          const showGroupAlert = key === 'workloads' && alertPodCount > 0 && !expanded
          return (
            <div key={key}>
              <button
                onClick={() => toggleGroup(key)}
                className="w-full flex items-center gap-3 px-3 py-2 rounded-lg text-sm text-slate-300
                           hover:bg-slate-800/70 transition-colors"
              >
                <Icon className="w-4 h-4 shrink-0" />
                <span className="flex-1 text-left">{groupLabel}</span>
                {showGroupAlert && (
                  <span className="flex h-2 w-2 rounded-full bg-red-500 shrink-0 mr-1" title={`${alertPodCount} pod alerts`} />
                )}
                <IconChevronDown className={`w-3.5 h-3.5 text-slate-500 transition-transform ${expanded ? '' : '-rotate-90'}`} />
              </button>
              {expanded && (
                <div className="mt-0.5 space-y-0.5">
                  {items.map((item) => {
                    const isPodsItem = item.suffix === 'pods' && alertPodCount > 0
                    return (
                      <NavLink
                        key={item.suffix}
                        to={item.clusterScoped
                          ? `/clusters/${clusterId}/${item.suffix}`
                          : `/clusters/${clusterId}/namespaces/${currentNs ?? '_'}/${item.suffix}`}
                        className={subLinkClass}
                        onClick={onClose}
                        end={item.clusterScoped}
                      >
                        <span>{item.label}</span>
                        {isPodsItem && (
                          <span
                            className="inline-flex items-center justify-center px-1.5 py-0.5 rounded-full text-[10px] font-bold bg-red-500/25 text-red-400 border border-red-500/30 shrink-0 leading-none"
                            title={`${alertPodCount} pod(s) with OOMKill/restarts`}
                          >
                            {alertPodCount}
                          </span>
                        )}
                      </NavLink>
                    )
                  })}
                </div>
              )}
            </div>
          )
        })}

        {isAdmin && (
          <>
            <div className="my-2 border-t border-slate-800" />
            <NavLink to="/settings/clusters" className={linkClass} onClick={onClose}>
              <IconServer className="w-4 h-4 shrink-0" /> Clusters
            </NavLink>
            <NavLink to="/settings/users" className={linkClass} onClick={onClose}>
              <IconUsers className="w-4 h-4 shrink-0" /> Users
            </NavLink>
            <NavLink to="/audit" className={linkClass} onClick={onClose}>
              <IconClipboard className="w-4 h-4 shrink-0" /> Audit log
            </NavLink>
            <NavLink to={`/clusters/${clusterId}/runbooks`} className={linkClass} onClick={onClose}>
              <IconLayers className="w-4 h-4 shrink-0" /> Runbooks
            </NavLink>
            <button
              onClick={() => { terminalPanel.openClusterTerminal(clusterId); onClose?.() }}
              className="flex items-center gap-3 px-3 py-2 rounded-lg text-sm w-full text-left
                         text-slate-400 hover:bg-slate-800/70 hover:text-slate-100 transition-colors"
            >
              <IconTerminal className="w-4 h-4 shrink-0" /> Cluster Terminal
            </button>
          </>
        )}
      </nav>

      {/* User footer */}
      <div className="shrink-0 px-4 py-3 border-t border-slate-800">
        <p className="text-sm font-medium text-slate-200 truncate">{username}</p>
        <button onClick={() => { logout(); onClose?.() }}
          className="mt-0.5 text-xs text-slate-500 hover:text-slate-300 transition-colors">
          Sign out
        </button>
      </div>
    </aside>
  )
}
