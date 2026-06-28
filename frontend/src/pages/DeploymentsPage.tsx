import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { Layout } from '../components/Layout'
import { StatusBadge } from '../components/StatusBadge'
import { DetailDrawer, DrawerRow } from '../components/DetailDrawer'
import { useSSE } from '../hooks/useSSE'
import { formatAge } from '../utils/format'
import type { Deployment } from '../types/k8s'

function replicaStatus(d: Deployment): string {
  if (d.readyReplicas === d.desiredReplicas) return 'Ready'
  if (d.readyReplicas === 0) return 'Failed'
  return 'Pending'
}

export function DeploymentsPage() {
  const { ns } = useParams<{ ns: string }>()
  const [selected, setSelected] = useState<Deployment | null>(null)

  const queryKey = ['deployments', ns]
  const { data, isLoading, isError, error } = useQuery<Deployment[]>({
    queryKey,
    queryFn: async () => (await api.get<Deployment[]>(`/k8s/namespaces/${ns}/deployments`)).data,
    enabled: !!ns,
  })

  useSSE<Deployment[]>(ns ? `/api/k8s/watch/namespaces/${ns}/deployments` : null, queryKey)

  return (
    <Layout>
      <div className="flex items-center justify-between mb-4">
        <div>
          <h1 className="text-xl font-semibold text-gray-800">Deployments</h1>
          {ns && <p className="text-sm text-gray-500 mt-0.5">namespace: <span className="font-medium">{ns}</span></p>}
        </div>
        {data && (
          <span className="text-sm text-gray-500">{data.length} deployment{data.length !== 1 ? 's' : ''}</span>
        )}
      </div>

      {isLoading && <p className="text-gray-500 text-sm">Loading deployments…</p>}
      {isError && (
        <div className="text-sm text-red-600 bg-red-50 rounded-md px-4 py-3">
          Could not load deployments: {(error as Error).message}
        </div>
      )}

      {data && (
        <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white shadow-sm">
          <table className="w-full text-sm min-w-[540px]">
            <thead>
              <tr className="bg-gray-50 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide">
                <th className="px-4 py-3">Name</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3">Ready</th>
                <th className="px-4 py-3">Strategy</th>
                <th className="px-4 py-3">Image</th>
                <th className="px-4 py-3">Age</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {data.map((d) => (
                <tr
                  key={`${d.namespace}/${d.name}`}
                  onClick={() => setSelected(d)}
                  className="hover:bg-blue-50 cursor-pointer transition-colors"
                >
                  <td className="px-4 py-3 font-medium text-gray-800">{d.name}</td>
                  <td className="px-4 py-3"><StatusBadge status={replicaStatus(d)} /></td>
                  <td className="px-4 py-3 text-gray-600">
                    {d.readyReplicas}/{d.desiredReplicas}
                  </td>
                  <td className="px-4 py-3 text-gray-500 text-xs">{d.strategy}</td>
                  <td className="px-4 py-3 text-gray-500 font-mono text-xs truncate max-w-[200px]">
                    {d.image ?? '—'}
                  </td>
                  <td className="px-4 py-3 text-gray-500">{formatAge(d.creationTimestamp)}</td>
                </tr>
              ))}
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
            <DrawerRow label="Status" value={<StatusBadge status={replicaStatus(selected)} />} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow
              label="Replicas"
              value={`${selected.readyReplicas} ready / ${selected.availableReplicas} available / ${selected.desiredReplicas} desired`}
            />
            <DrawerRow label="Strategy" value={selected.strategy} />
            <DrawerRow label="Image" value={selected.image} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
