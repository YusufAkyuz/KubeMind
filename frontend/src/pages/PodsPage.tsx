import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { Layout } from '../components/Layout'
import { StatusBadge } from '../components/StatusBadge'
import { DetailDrawer, DrawerRow } from '../components/DetailDrawer'
import { useSSE } from '../hooks/useSSE'
import { formatAge } from '../utils/format'
import type { Pod } from '../types/k8s'

export function PodsPage() {
  const { ns } = useParams<{ ns: string }>()
  const [selected, setSelected] = useState<Pod | null>(null)

  const queryKey = ['pods', ns]
  const { data, isLoading, isError, error } = useQuery<Pod[]>({
    queryKey,
    queryFn: async () => (await api.get<Pod[]>(`/k8s/namespaces/${ns}/pods`)).data,
    enabled: !!ns,
  })

  useSSE<Pod[]>(ns ? `/api/k8s/watch/namespaces/${ns}/pods` : null, queryKey)

  return (
    <Layout>
      <div className="flex items-center justify-between mb-4">
        <div>
          <h1 className="text-xl font-semibold text-gray-800">Pods</h1>
          {ns && <p className="text-sm text-gray-500 mt-0.5">namespace: <span className="font-medium">{ns}</span></p>}
        </div>
        {data && (
          <span className="text-sm text-gray-500">{data.length} pod{data.length !== 1 ? 's' : ''}</span>
        )}
      </div>

      {isLoading && <p className="text-gray-500 text-sm">Loading pods…</p>}
      {isError && (
        <div className="text-sm text-red-600 bg-red-50 rounded-md px-4 py-3">
          Could not load pods: {(error as Error).message}
        </div>
      )}

      {data && (
        <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white shadow-sm">
          <table className="w-full text-sm min-w-[640px]">
            <thead>
              <tr className="bg-gray-50 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide">
                <th className="px-4 py-3">Name</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3">Ready</th>
                <th className="px-4 py-3">Restarts</th>
                <th className="px-4 py-3">Node</th>
                <th className="px-4 py-3">IP</th>
                <th className="px-4 py-3">Age</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {data.map((pod) => {
                const readyCount = pod.containers.filter((c) => c.ready).length
                const highRestarts = pod.restartCount > 5
                return (
                  <tr
                    key={`${pod.namespace}/${pod.name}`}
                    onClick={() => setSelected(pod)}
                    className="hover:bg-blue-50 cursor-pointer transition-colors"
                  >
                    <td className="px-4 py-3 font-medium text-gray-800">{pod.name}</td>
                    <td className="px-4 py-3"><StatusBadge status={pod.phase} /></td>
                    <td className="px-4 py-3 text-gray-600">
                      {readyCount}/{pod.containers.length}
                    </td>
                    <td className="px-4 py-3">
                      <span className={highRestarts ? 'text-red-600 font-semibold' : 'text-gray-600'}>
                        {pod.restartCount}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-500 text-xs">{pod.nodeName ?? '—'}</td>
                    <td className="px-4 py-3 text-gray-500 font-mono text-xs">{pod.podIP ?? '—'}</td>
                    <td className="px-4 py-3 text-gray-500">{formatAge(pod.creationTimestamp)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      <DetailDrawer
        open={selected !== null}
        title={selected?.name ?? ''}
        onClose={() => setSelected(null)}
      >
        {selected && (
          <>
            <DrawerRow label="Status" value={<StatusBadge status={selected.phase} />} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Node" value={selected.nodeName} />
            <DrawerRow label="Pod IP" value={selected.podIP} />
            <DrawerRow label="Restarts" value={selected.restartCount} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />

            {selected.containers.length > 0 && (
              <div>
                <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">Containers</p>
                <div className="space-y-2">
                  {selected.containers.map((c) => (
                    <div key={c.name} className="rounded-md border border-gray-200 px-3 py-2 text-sm">
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-medium text-gray-800">{c.name}</span>
                        <StatusBadge status={c.ready ? 'Ready' : 'NotReady'} />
                      </div>
                      <div className="text-gray-500 text-xs mt-1 font-mono break-all">{c.image}</div>
                      <div className="text-gray-500 text-xs mt-0.5">Restarts: {c.restartCount}</div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
