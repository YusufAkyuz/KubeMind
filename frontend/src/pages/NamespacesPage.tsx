import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { StatusBadge } from '../components/StatusBadge'
import { ErrorBanner } from '../components/ErrorBanner'
import { formatAge } from '../utils/format'
import type { Namespace } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'age', label: 'Age' },
]

export function NamespacesPage() {
  const navigate = useNavigate()
  const { data, isLoading, isError, error } = useQuery<Namespace[]>({
    queryKey: ['namespaces'],
    queryFn: async () => (await api.get<Namespace[]>('/k8s/namespaces')).data,
  })

  return (
    <Layout>
      <PageHeader title="Namespaces" count={data?.length} noun="namespace" />

      {isLoading && <p className="text-sm text-gray-400">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load namespaces: ${(error as Error).message}`} />}

      {data && (
        <Table columns={COLUMNS} minWidth="360px">
          {data.map((ns) => (
            <Tr key={ns.name} onClick={() => navigate(`/namespaces/${ns.name}/pods`)}>
              <Td className="font-medium text-blue-600">{ns.name}</Td>
              <Td><StatusBadge status={ns.phase ?? 'Unknown'} /></Td>
              <Td className="text-gray-400 tabular-nums">{formatAge(ns.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}
    </Layout>
  )
}
