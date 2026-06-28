import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthContext'

interface Namespace {
  name: string
  phase: string | null
  creationTimestamp: string | null
}

export function NamespacesPage() {
  const { username, logout } = useAuth()
  const { data, isLoading, isError, error } = useQuery<Namespace[]>({
    queryKey: ['namespaces'],
    queryFn: async () => (await api.get<Namespace[]>('/k8s/namespaces')).data,
  })

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="flex items-center justify-between bg-white border-b px-6 py-4">
        <h1 className="text-xl font-semibold text-gray-800">KubeMind · Namespaces</h1>
        <div className="flex items-center gap-4 text-sm text-gray-600">
          <span>{username}</span>
          <button onClick={() => logout()} className="text-blue-600 hover:underline">
            Sign out
          </button>
        </div>
      </header>

      <main className="p-6">
        {isLoading && <p className="text-gray-500">Loading namespaces…</p>}

        {isError && (
          <div className="text-red-600">
            Could not load namespaces: {(error as Error).message}. Is your cluster reachable?
          </div>
        )}

        {data && (
          <table className="w-full bg-white rounded-lg shadow overflow-hidden text-sm">
            <thead className="bg-gray-100 text-left text-gray-600">
              <tr>
                <th className="px-4 py-2">Name</th>
                <th className="px-4 py-2">Status</th>
                <th className="px-4 py-2">Created</th>
              </tr>
            </thead>
            <tbody>
              {data.map((ns) => (
                <tr key={ns.name} className="border-t border-gray-100">
                  <td className="px-4 py-2 font-medium text-gray-800">{ns.name}</td>
                  <td className="px-4 py-2">{ns.phase ?? '—'}</td>
                  <td className="px-4 py-2 text-gray-500">{ns.creationTimestamp ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </main>
    </div>
  )
}
