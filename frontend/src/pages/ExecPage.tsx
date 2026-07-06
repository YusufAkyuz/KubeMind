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
    <Layout>
      {/* Breadcrumb */}
      <nav className="flex items-center gap-1.5 text-sm text-gray-400 mb-4 flex-wrap">
        <Link to={`/clusters/${clusterId}/namespaces/${ns}/pods`} className="hover:text-gray-700 transition-colors">
          {ns}
        </Link>
        <IconChevronRight className="w-3.5 h-3.5 shrink-0" />
        <span className="text-gray-600">{pod}</span>
        <IconChevronRight className="w-3.5 h-3.5 shrink-0" />
        <span className="text-gray-600 font-medium">Terminal</span>
      </nav>

      <div className="rounded-xl border border-gray-200 bg-gray-950 overflow-hidden shadow-lg flex flex-col"
           style={{ height: 'calc(100vh - 11rem)' }}>
        {/* Toolbar */}
        <div className="flex items-center gap-3 px-4 py-2.5 border-b border-gray-800 bg-gray-900 shrink-0">
          {podData && podData.containers.length > 1 ? (
            <select
              value={containerName}
              onChange={handleContainerChange}
              className="h-7 rounded-md border border-gray-700 bg-gray-800 px-2 text-xs text-gray-200
                         focus:outline-none focus:ring-1 focus:ring-blue-500"
            >
              {podData.containers.map((c) => (
                <option key={c.name} value={c.name}>{c.name}</option>
              ))}
            </select>
          ) : (
            <span className="text-xs text-gray-400 font-mono">{containerName}</span>
          )}
          <div className="ml-auto flex items-center gap-2">
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

        {/* Terminal surface */}
        <div ref={termElRef} className="flex-1 overflow-hidden p-2" />
      </div>

      <p className="mt-2 text-xs text-gray-400">
        Interactive shell (/bin/sh). This session is ADMIN-only and recorded in the audit log.
      </p>
    </Layout>
  )
}
