import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { Layout } from '../components/Layout'
import { IconChevronRight } from '../components/Icons'
import { useWsTerminal } from '../hooks/useWsTerminal'
import type { Pod } from '../types/k8s'

export function ExecPage() {
  const { clusterId, ns, pod } = useParams<{ clusterId: string; ns: string; pod: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const container = searchParams.get('container') ?? ''

  const { data: podData } = useQuery<Pod>({
    queryKey: ['pod', clusterId, ns, pod],
    queryFn: async () => (await api.get<Pod>(`/clusters/${clusterId}/namespaces/${ns}/pods/${pod}`)).data,
    enabled: !!clusterId && !!ns && !!pod,
    staleTime: 60_000,
  })

  const containerName = container || podData?.containers[0]?.name || ''

  const wsUrl = clusterId && ns && pod && containerName
    ? `/ws/exec?clusterId=${clusterId}&ns=${encodeURIComponent(ns)}&pod=${encodeURIComponent(pod)}`
      + `&container=${encodeURIComponent(containerName)}`
    : null

  const { termElRef, connState } = useWsTerminal(wsUrl, !!wsUrl)

  const handleContainerChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setSearchParams({ container: e.target.value })
  }

  return (
    <Layout fullBleed>
      <div className="h-full w-full flex flex-col bg-gray-950">
        {/* Toolbar (breadcrumb + container selector + status, all in one bar) */}
        <div className="flex items-center gap-3 px-4 py-2.5 border-b border-gray-800 bg-gray-900 shrink-0">
          <nav className="flex items-center gap-1.5 text-xs text-gray-500 min-w-0">
            <Link to={`/clusters/${clusterId}/namespaces/${ns}/pods`} className="hover:text-gray-300 transition-colors truncate">
              {ns}
            </Link>
            <IconChevronRight className="w-3 h-3 shrink-0" />
            <span className="text-gray-300 truncate">{pod}</span>
          </nav>

          {podData && podData.containers.length > 1 ? (
            <select
              value={containerName}
              onChange={handleContainerChange}
              className="shrink-0 h-7 rounded-md border border-gray-700 bg-gray-800 px-2 text-xs text-gray-200
                         focus:outline-none focus:ring-1 focus:ring-blue-500"
            >
              {podData.containers.map((c) => (
                <option key={c.name} value={c.name}>{c.name}</option>
              ))}
            </select>
          ) : (
            <span className="shrink-0 text-xs text-gray-500 font-mono">{containerName}</span>
          )}

          <div className="ml-auto flex items-center gap-2 shrink-0">
            <span className={`w-1.5 h-1.5 rounded-full ${
              connState === 'connected' ? 'bg-emerald-400'
              : connState === 'connecting' ? 'bg-amber-400 animate-pulse' : 'bg-gray-500'
            }`} />
            <span className="text-[10px] text-gray-500 uppercase tracking-wide">
              {connState === 'connected' ? 'Connected'
                : connState === 'connecting' ? 'Connecting…' : 'Closed'}
            </span>
          </div>
        </div>

        {/* Terminal surface fills all remaining space */}
        <div ref={termElRef} className="flex-1 min-h-0 overflow-hidden p-2" />
      </div>
    </Layout>
  )
}
