import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner } from '../components/ErrorBanner'
import { SystemManagedToggle } from '../components/SystemManagedToggle'
import { CreateClusterResourceButton } from '../components/CreateClusterResourceButton'
import { EditYamlButton } from '../components/EditYamlButton'
import { RbacRules } from '../components/RbacDetails'
import { useAuth } from '../auth/AuthContext'
import { formatAge } from '../utils/format'
import type { ClusterRole } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'rules', label: 'Rules', className: 'hidden md:table-cell' },
  { key: 'age', label: 'Age' },
]

// Cluster-scoped, so this page doesn't use the namespaced-list hook.
export function ClusterRolesPage() {
  const { clusterId } = useParams<{ clusterId: string }>()
  const { isAdmin } = useAuth()
  const [selected, setSelected] = useState<ClusterRole | null>(null)
  const [showSystem, setShowSystem] = useState(false)

  const { data, isLoading, isError, error } = useQuery<ClusterRole[]>({
    queryKey: ['clusterroles', clusterId],
    queryFn: async () => (await api.get<ClusterRole[]>(`/clusters/${clusterId}/clusterroles`)).data,
    enabled: !!clusterId,
    refetchInterval: 20_000,
  })

  const visible = (data ?? []).filter((cr) => showSystem || !cr.systemManaged)
  const hiddenCount = (data?.length ?? 0) - visible.length

  return (
    <Layout>
      <PageHeader title="Cluster Roles" subtitle="cluster-scoped" count={visible.length} noun="cluster role"
                  actions={<CreateClusterResourceButton clusterId={clusterId} kind="ClusterRole" />} />

      {isLoading && <p className="text-sm text-gray-400">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load cluster roles: ${(error as Error).message}`} />}

      {data && (
        <div className="space-y-2">
          <SystemManagedToggle checked={showSystem} onChange={setShowSystem} hiddenCount={hiddenCount} />
          <Table columns={COLUMNS}>
            {visible.map((cr) => (
              <Tr key={cr.name} onClick={() => setSelected(cr)} highlighted={selected?.name === cr.name}>
                <Td className="font-medium text-gray-900">{cr.name}</Td>
                <Td className="hidden md:table-cell text-gray-500 tabular-nums">{cr.rules.length}</Td>
                <Td className="text-gray-400 tabular-nums">{formatAge(cr.creationTimestamp)}</Td>
              </Tr>
            ))}
          </Table>
        </div>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle="ClusterRole" onClose={() => setSelected(null)}>
        {selected && (
          <>
            {isAdmin && (
              <div className="pb-3 flex flex-wrap gap-2">
                <EditYamlButton clusterId={clusterId!} kind="ClusterRole" name={selected.name} />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />
            <RbacRules rules={selected.rules} />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
