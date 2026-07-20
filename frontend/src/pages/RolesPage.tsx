import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td, withNamespaceColumn } from '../components/Table'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner, EmptyState } from '../components/ErrorBanner'
import { SystemManagedToggle } from '../components/SystemManagedToggle'
import { CreateResourceButton } from '../components/CreateResourceButton'
import { EditYamlButton } from '../components/EditYamlButton'
import { DeleteResourceButton } from '../components/DeleteResourceButton'
import { RbacRules } from '../components/RbacDetails'
import { useNamespacedList, noNamespaceMessage } from '../hooks/useNamespacedList'
import { useAuth } from '../auth/AuthContext'
import { formatAge } from '../utils/format'
import type { Role } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'rules', label: 'Rules', className: 'hidden md:table-cell' },
  { key: 'age', label: 'Age' },
]

export function RolesPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<Role>('roles')
  const { isAdmin } = useAuth()
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<Role | null>(null)
  const [showSystem, setShowSystem] = useState(false)
  const showNsColumn = ns === 'all'

  const visible = (data ?? []).filter((r) => showSystem || !r.systemManaged)
  const hiddenCount = (data?.length ?? 0) - visible.length

  return (
    <Layout>
      <PageHeader title="Roles"
                  subtitle={ns === 'all' ? 'All namespaces' : ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={visible.length} noun="role"
                  actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="Role" />} />

      {noNamespace && <EmptyState message={noNamespaceMessage('roles')} />}
      {isLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load roles: ${(error as Error).message}`} />}

      {data && (
        <div className="space-y-2">
          <SystemManagedToggle checked={showSystem} onChange={setShowSystem} hiddenCount={hiddenCount} />
          <Table columns={withNamespaceColumn(COLUMNS, showNsColumn)}>
            {visible.map((r) => (
              <Tr key={`${r.namespace}/${r.name}`} onClick={() => setSelected(r)}
                  highlighted={selected?.name === r.name && selected?.namespace === r.namespace}>
                <Td className="font-medium text-gray-900 dark:text-neutral-100">{r.name}</Td>
                {showNsColumn && <Td className="text-gray-500 dark:text-neutral-400">{r.namespace}</Td>}
                <Td className="hidden md:table-cell text-gray-500 dark:text-neutral-400 tabular-nums">{r.rules.length}</Td>
                <Td className="text-gray-400 dark:text-neutral-500 tabular-nums">{formatAge(r.creationTimestamp)}</Td>
              </Tr>
            ))}
          </Table>
        </div>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`Role · ${selected?.namespace ?? ns}`}
                    onClose={() => setSelected(null)}>
        {selected && (
          <>
            {isAdmin && (
              <div className="pb-3 flex flex-wrap gap-2">
                <EditYamlButton clusterId={clusterId!} ns={selected.namespace} kind="Role" name={selected.name} />
                <DeleteResourceButton
                  clusterId={clusterId!} ns={selected.namespace} kind="Role" name={selected.name}
                  onDeleted={() => { setSelected(null); queryClient.invalidateQueries({ queryKey: ['roles', clusterId, ns] }) }}
                />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />
            <RbacRules rules={selected.rules} />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
