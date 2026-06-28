import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { Layout } from '../components/Layout'
import { StatusBadge } from '../components/StatusBadge'
import { formatAge } from '../utils/format'
import type { Namespace } from '../types/k8s'

export function NamespacesPage() {
  const navigate = useNavigate()
  const { data, isLoading, isError, error } = useQuery<Namespace[]>({
    queryKey: ['namespaces'],
    queryFn: async () => (await api.get<Namespace[]>('/k8s/namespaces')).data,
  })

  return (
    <Layout>
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-xl font-semibold text-gray-800">Namespaces</h1>
        {data && (
          <span className="text-sm text-gray-500">{data.length} namespace{data.length !== 1 ? 's' : ''}</span>
        )}
      </div>

      {isLoading && <p className="text-gray-500 text-sm">Loading namespaces…</p>}
      {isError && (
        <div className="text-sm text-red-600 bg-red-50 rounded-md px-4 py-3">
          Could not load namespaces: {(error as Error).message}. Is your cluster reachable?
        </div>
      )}

      {data && (
        <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white shadow-sm">
          <table className="w-full text-sm min-w-[400px]">
            <thead>
              <tr className="bg-gray-50 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide">
                <th className="px-4 py-3">Name</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3">Age</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {data.map((ns) => (
                <tr
                  key={ns.name}
                  onClick={() => navigate(`/namespaces/${ns.name}/pods`)}
                  className="hover:bg-blue-50 cursor-pointer transition-colors"
                >
                  <td className="px-4 py-3 font-medium text-blue-700">{ns.name}</td>
                  <td className="px-4 py-3">
                    <StatusBadge status={ns.phase ?? 'Unknown'} />
                  </td>
                  <td className="px-4 py-3 text-gray-500">{formatAge(ns.creationTimestamp)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Layout>
  )
}
