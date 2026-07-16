import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { formatAge } from '../utils/format'
import { renderLiteMarkdown } from '../utils/markdownLite'
import type { ClusterInsights } from '../types/k8s'

interface Props {
  clusterId: string
}

/** The AI's rolling background summary of cluster health — see ClusterProfileService (backend). */
export function ClusterInsightsPanel({ clusterId }: Props) {
  const { data, isLoading } = useQuery<ClusterInsights>({
    queryKey: ['cluster-insights', clusterId],
    queryFn: async () => (await api.get<ClusterInsights>(`/clusters/${clusterId}/cluster-insights`)).data,
    enabled: !!clusterId,
    refetchInterval: 60_000,
  })

  if (isLoading) return null
  if (!data || !data.available) return null

  return (
    <div className="mb-4 rounded-lg border border-gray-200 dark:border-slate-700 overflow-hidden">
      <div className="flex items-center justify-between px-4 py-3 bg-gray-50 dark:bg-slate-800/60 border-b border-gray-200 dark:border-slate-700">
        <p className="text-sm font-medium text-gray-900 dark:text-slate-100">Cluster Insights</p>
        {data.lastUpdated && (
          <p className="text-xs text-gray-400 dark:text-slate-500">updated {formatAge(data.lastUpdated)} ago</p>
        )}
      </div>

      <div className="px-4 py-3 space-y-3">
        <div className="flex flex-wrap gap-x-6 gap-y-1 text-sm text-gray-700 dark:text-slate-300">
          <span>{data.nodeCount} node{data.nodeCount === 1 ? '' : 's'}
            {data.nodeVersions.length > 0 && <span className="text-gray-400 dark:text-slate-500"> ({data.nodeVersions.join(', ')})</span>}
          </span>
          <span>{data.namespaceCount} namespace{data.namespaceCount === 1 ? '' : 's'}</span>
          <span>{data.podCount} pod{data.podCount === 1 ? '' : 's'}</span>
          <span className={data.unhealthyPodCount > 0 ? 'text-amber-700 dark:text-amber-400 font-medium' : 'text-gray-700 dark:text-slate-300'}>
            {data.unhealthyPodCount} unhealthy
          </span>
        </div>

        {data.incidentNarrative && (
          <div className="space-y-1.5">{renderLiteMarkdown(data.incidentNarrative)}</div>
        )}

        {data.recentChanges.length > 0 && (
          <div>
            <p className="text-xs font-semibold text-gray-500 dark:text-slate-400 uppercase tracking-wider mb-1">Recent changes</p>
            <ul className="space-y-0.5">
              {data.recentChanges.slice(0, 5).map((c, i) => (
                <li key={i} className="text-xs text-gray-500 dark:text-slate-400 font-mono">
                  {c.action} <span className="text-gray-700 dark:text-slate-300">{c.resourceRef}</span> ({c.result})
                </li>
              ))}
            </ul>
          </div>
        )}

        <p className="pt-2 border-t border-gray-100 dark:border-slate-800 text-xs text-gray-400 dark:text-slate-500">
          AI-generated — verify before acting.
        </p>
      </div>
    </div>
  )
}
