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
import { RbacSubjects } from '../components/RbacDetails'
import { useAuth } from '../auth/AuthContext'
import { formatAge } from '../utils/format'
import type { ClusterRoleBinding } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'roleRef', label: 'Role ref', className: 'hidden md:table-cell' },
  { key: 'subjects', label: 'Subjects', className: 'hidden lg:table-cell' },
  { key: 'age', label: 'Age' },
]

// Cluster-scoped, so this page doesn't use the namespaced-list hook.
export function ClusterRoleBindingsPage() {
  const { clusterId } = useParams<{ clusterId: string }>()
  const { isAdmin } = useAuth()
  const [selected, setSelected] = useState<ClusterRoleBinding | null>(null)
  const [showSystem, setShowSystem] = useState(false)

  const { data, isLoading, isError, error } = useQuery<ClusterRoleBinding[]>({
    queryKey: ['clusterrolebindings', clusterId],
    queryFn: async () => (await api.get<ClusterRoleBinding[]>(`/clusters/${clusterId}/clusterrolebindings`)).data,
    enabled: !!clusterId,
    refetchInterval: 20_000,
  })

  const visible = (data ?? []).filter((crb) => showSystem || !crb.systemManaged)
  const hiddenCount = (data?.length ?? 0) - visible.length

  return (
    <Layout>
      <PageHeader title="Cluster Role Bindings" subtitle="cluster-scoped" count={visible.length} noun="cluster role binding"
                  actions={<CreateClusterResourceButton clusterId={clusterId} kind="ClusterRoleBinding" />} />

      {isLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load cluster role bindings: ${(error as Error).message}`} />}

      {data && (
        <div className="space-y-2">
          <SystemManagedToggle checked={showSystem} onChange={setShowSystem} hiddenCount={hiddenCount} />
          <Table columns={COLUMNS}>
            {visible.map((crb) => (
              <Tr key={crb.name} onClick={() => setSelected(crb)} highlighted={selected?.name === crb.name}>
                <Td className="font-medium text-gray-900 dark:text-neutral-100">{crb.name}</Td>
                <Td className="hidden md:table-cell font-mono text-xs text-gray-500 dark:text-neutral-400">{crb.roleRefKind}/{crb.roleRefName}</Td>
                <Td className="hidden lg:table-cell text-gray-500 dark:text-neutral-400 tabular-nums">{crb.subjects.length}</Td>
                <Td className="text-gray-400 dark:text-neutral-500 tabular-nums">{formatAge(crb.creationTimestamp)}</Td>
              </Tr>
            ))}
          </Table>
        </div>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle="ClusterRoleBinding" onClose={() => setSelected(null)}>
        {selected && (
          <>
            {isAdmin && (
              <div className="pb-3 flex flex-wrap gap-2">
                <EditYamlButton clusterId={clusterId!} kind="ClusterRoleBinding" name={selected.name} />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />
            <RbacSubjects subjects={selected.subjects} roleRefKind={selected.roleRefKind} roleRefName={selected.roleRefName} />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
