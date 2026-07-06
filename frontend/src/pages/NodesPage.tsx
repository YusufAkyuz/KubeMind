import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { StatusBadge } from '../components/StatusBadge'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner } from '../components/ErrorBanner'
import { useSSE } from '../hooks/useSSE'
import { useAuth } from '../auth/AuthContext'
import { formatAge } from '../utils/format'
import type { NodeResource } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'roles', label: 'Roles' },
  { key: 'version', label: 'Version', className: 'hidden md:table-cell' },
  { key: 'cpu', label: 'CPU', className: 'hidden lg:table-cell' },
  { key: 'memory', label: 'Memory', className: 'hidden lg:table-cell' },
  { key: 'age', label: 'Age' },
]

export function NodesPage() {
  const { clusterId } = useParams<{ clusterId: string }>()
  const navigate = useNavigate()
  const { isAdmin } = useAuth()
  const [selected, setSelected] = useState<NodeResource | null>(null)
  const queryKey = ['nodes', clusterId]

  const { data, isLoading, isError, error } = useQuery<NodeResource[]>({
    queryKey,
    queryFn: async () => (await api.get<NodeResource[]>(`/clusters/${clusterId}/nodes`)).data,
    enabled: !!clusterId,
  })

  useSSE<NodeResource[]>(clusterId ? `/api/clusters/${clusterId}/watch/nodes` : null, queryKey)

  return (
    <Layout>
      <PageHeader title="Nodes" count={data?.length} noun="node" />

      {isLoading && <p className="text-sm text-gray-400">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load nodes: ${(error as Error).message}`} />}

      {data && (
        <Table columns={COLUMNS}>
          {data.map((node) => (
            <Tr
              key={node.name}
              onClick={() => setSelected(node)}
              highlighted={selected?.name === node.name}
            >
              <Td className="font-medium text-gray-900">{node.name}</Td>
              <Td><StatusBadge status={node.status} /></Td>
              <Td className="text-gray-500">{node.roles}</Td>
              <Td className="hidden md:table-cell font-mono text-xs text-gray-400">{node.kubeletVersion ?? '—'}</Td>
              <Td className="hidden lg:table-cell text-gray-500">{node.cpuCapacity ?? '—'}</Td>
              <Td className="hidden lg:table-cell text-gray-500">{node.memoryCapacity ?? '—'}</Td>
              <Td className="text-gray-400 tabular-nums">{formatAge(node.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer
        open={!!selected}
        title={selected?.name ?? ''}
        subtitle="Node"
        onClose={() => setSelected(null)}
      >
        {selected && (
          <>
            {isAdmin && (
              <div className="pb-3">
                <button
                  onClick={() => navigate(`/clusters/${clusterId}/nodes/${selected.name}/exec`)}
                  className="rounded-md border border-amber-300 bg-amber-50 px-3 py-1.5 text-xs font-medium
                             text-amber-800 hover:bg-amber-100 transition-colors"
                >
                  Node shell
                </button>
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Status" value={<StatusBadge status={selected.status} />} />
            <DrawerRow label="Roles" value={selected.roles} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />

            <DrawerSection title="System" />
            <DrawerRow label="Kubelet" value={selected.kubeletVersion} />
            <DrawerRow label="OS" value={selected.osImage} />

            <DrawerSection title="Resources" />
            <DrawerRow label="CPU (capacity)" value={selected.cpuCapacity} />
            <DrawerRow label="CPU (allocatable)" value={selected.cpuAllocatable} />
            <DrawerRow label="Memory (capacity)" value={selected.memoryCapacity} />
            <DrawerRow label="Memory (allocatable)" value={selected.memoryAllocatable} />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
