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
import { RbacSubjects } from '../components/RbacDetails'
import { useNamespacedList, noNamespaceMessage } from '../hooks/useNamespacedList'
import { useAuth } from '../auth/AuthContext'
import { formatAge } from '../utils/format'
import type { RoleBinding } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'roleRef', label: 'Role ref', className: 'hidden md:table-cell' },
  { key: 'subjects', label: 'Subjects', className: 'hidden lg:table-cell' },
  { key: 'age', label: 'Age' },
]

export function RoleBindingsPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<RoleBinding>('rolebindings')
  const { isAdmin } = useAuth()
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<RoleBinding | null>(null)
  const [showSystem, setShowSystem] = useState(false)
  const showNsColumn = ns === 'all'

  const visible = (data ?? []).filter((rb) => showSystem || !rb.systemManaged)
  const hiddenCount = (data?.length ?? 0) - visible.length

  return (
    <Layout>
      <PageHeader title="Role Bindings"
                  subtitle={ns === 'all' ? 'All namespaces' : ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={visible.length} noun="role binding"
                  actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="RoleBinding" />} />

      {noNamespace && <EmptyState message={noNamespaceMessage('role bindings')} />}
      {isLoading && <p className="text-sm text-gray-400 dark:text-slate-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load role bindings: ${(error as Error).message}`} />}

      {data && (
        <div className="space-y-2">
          <SystemManagedToggle checked={showSystem} onChange={setShowSystem} hiddenCount={hiddenCount} />
          <Table columns={withNamespaceColumn(COLUMNS, showNsColumn)}>
            {visible.map((rb) => (
              <Tr key={`${rb.namespace}/${rb.name}`} onClick={() => setSelected(rb)}
                  highlighted={selected?.name === rb.name && selected?.namespace === rb.namespace}>
                <Td className="font-medium text-gray-900 dark:text-slate-100">{rb.name}</Td>
                {showNsColumn && <Td className="text-gray-500 dark:text-slate-400">{rb.namespace}</Td>}
                <Td className="hidden md:table-cell font-mono text-xs text-gray-500 dark:text-slate-400">{rb.roleRefKind}/{rb.roleRefName}</Td>
                <Td className="hidden lg:table-cell text-gray-500 dark:text-slate-400 tabular-nums">{rb.subjects.length}</Td>
                <Td className="text-gray-400 dark:text-slate-500 tabular-nums">{formatAge(rb.creationTimestamp)}</Td>
              </Tr>
            ))}
          </Table>
        </div>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`RoleBinding · ${selected?.namespace ?? ns}`}
                    onClose={() => setSelected(null)}>
        {selected && (
          <>
            {isAdmin && (
              <div className="pb-3 flex flex-wrap gap-2">
                <EditYamlButton clusterId={clusterId!} ns={selected.namespace} kind="RoleBinding" name={selected.name} />
                <DeleteResourceButton
                  clusterId={clusterId!} ns={selected.namespace} kind="RoleBinding" name={selected.name}
                  onDeleted={() => { setSelected(null); queryClient.invalidateQueries({ queryKey: ['rolebindings', clusterId, ns] }) }}
                />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />
            <RbacSubjects subjects={selected.subjects} roleRefKind={selected.roleRefKind} roleRefName={selected.roleRefName} />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
