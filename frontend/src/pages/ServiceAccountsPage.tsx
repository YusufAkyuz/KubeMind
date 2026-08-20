import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { apiErrorMessage } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td, withNamespaceColumn } from '../components/Table'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner, EmptyState } from '../components/ErrorBanner'
import { SystemManagedToggle } from '../components/SystemManagedToggle'
import { CreateResourceButton } from '../components/CreateResourceButton'
import { EditYamlButton } from '../components/EditYamlButton'
import { DeleteResourceButton } from '../components/DeleteResourceButton'
import { useNamespacedList, noNamespaceMessage } from '../hooks/useNamespacedList'
import { useCanWrite } from '../auth/useCanWrite'
import { formatAge } from '../utils/format'
import type { ServiceAccount } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'secrets', label: 'Secrets', className: 'hidden md:table-cell' },
  { key: 'automount', label: 'Automount token', className: 'hidden lg:table-cell' },
  { key: 'age', label: 'Age' },
]

export function ServiceAccountsPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<ServiceAccount>('serviceaccounts')
  const canWrite = useCanWrite(clusterId)
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<ServiceAccount | null>(null)
  const [showSystem, setShowSystem] = useState(false)
  const showNsColumn = ns === 'all'

  const visible = (data ?? []).filter((sa) => showSystem || !sa.systemManaged)
  const hiddenCount = (data?.length ?? 0) - visible.length

  return (
    <Layout>
      <PageHeader title="Service Accounts"
                  subtitle={ns === 'all' ? 'All namespaces' : ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={visible.length} noun="service account"
                  actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="ServiceAccount" />} />

      {noNamespace && <EmptyState message={noNamespaceMessage('service accounts')} />}
      {isLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load service accounts: ${apiErrorMessage(error)}`} />}

      {data && (
        <div className="space-y-2">
          <SystemManagedToggle checked={showSystem} onChange={setShowSystem} hiddenCount={hiddenCount} />
          <Table columns={withNamespaceColumn(COLUMNS, showNsColumn)}>
            {visible.map((sa) => (
              <Tr key={`${sa.namespace}/${sa.name}`} onClick={() => setSelected(sa)}
                  highlighted={selected?.name === sa.name && selected?.namespace === sa.namespace}>
                <Td className="font-medium text-gray-900 dark:text-neutral-100">{sa.name}</Td>
                {showNsColumn && <Td className="text-gray-500 dark:text-neutral-400">{sa.namespace}</Td>}
                <Td className="hidden md:table-cell text-gray-500 dark:text-neutral-400 tabular-nums">
                  {sa.secretCount}{sa.imagePullSecretCount > 0 && ` (+${sa.imagePullSecretCount} pull)`}
                </Td>
                <Td className="hidden lg:table-cell text-gray-500 dark:text-neutral-400">{sa.automountToken === false ? 'Disabled' : 'Enabled'}</Td>
                <Td className="text-gray-400 dark:text-neutral-500 tabular-nums">{formatAge(sa.creationTimestamp)}</Td>
              </Tr>
            ))}
          </Table>
        </div>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`ServiceAccount · ${selected?.namespace ?? ns}`}
                    onClose={() => setSelected(null)}>
        {selected && (
          <>
            {canWrite && (
              <div className="pb-3 flex flex-wrap gap-2">
                <EditYamlButton clusterId={clusterId!} ns={selected.namespace} kind="ServiceAccount" name={selected.name} />
                <DeleteResourceButton
                  clusterId={clusterId!} ns={selected.namespace} kind="ServiceAccount" name={selected.name}
                  onDeleted={() => { setSelected(null); queryClient.invalidateQueries({ queryKey: ['serviceaccounts', clusterId, ns] }) }}
                />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Secrets" value={selected.secretCount} />
            <DrawerRow label="Image pull secrets" value={selected.imagePullSecretCount} />
            <DrawerRow label="Automount token" value={selected.automountToken === false ? 'Disabled' : 'Enabled'} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
