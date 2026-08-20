import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { apiErrorMessage } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td, withNamespaceColumn } from '../components/Table'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner, EmptyState } from '../components/ErrorBanner'
import { useNamespacedList, noNamespaceMessage } from '../hooks/useNamespacedList'
import { CreateResourceButton } from '../components/CreateResourceButton'
import { EditYamlButton } from '../components/EditYamlButton'
import { ForwardServiceButton } from '../components/ForwardServiceButton'
import { DeleteResourceButton } from '../components/DeleteResourceButton'
import { ExplainPanel } from '../components/ExplainPanel'
import { useCanWrite } from '../auth/useCanWrite'
import { formatAge } from '../utils/format'
import type { ServiceResource } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'type', label: 'Type' },
  { key: 'clusterIP', label: 'Cluster IP', className: 'hidden md:table-cell' },
  { key: 'ports', label: 'Ports' },
  { key: 'age', label: 'Age' },
]

export function ServicesPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<ServiceResource>('services')
  const canWrite = useCanWrite(clusterId)
  const [selected, setSelected] = useState<ServiceResource | null>(null)
  const queryClient = useQueryClient()
  const showNsColumn = ns === 'all'

  return (
    <Layout>
      <PageHeader title="Services"
                  subtitle={ns === 'all' ? 'All namespaces' : ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={data?.length} noun="service"
                  actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="Service" />} />

      {noNamespace && <EmptyState message={noNamespaceMessage('services')} />}
      {isLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load services: ${apiErrorMessage(error)}`} />}

      {data && (
        <Table columns={withNamespaceColumn(COLUMNS, showNsColumn)}>
          {data.map((s) => (
            <Tr key={`${s.namespace}/${s.name}`} onClick={() => setSelected(s)}
                highlighted={selected?.name === s.name && selected?.namespace === s.namespace}>
              <Td className="font-medium text-gray-900 dark:text-neutral-100">{s.name}</Td>
              {showNsColumn && <Td className="text-gray-500 dark:text-neutral-400">{s.namespace}</Td>}
              <Td className="text-gray-500 dark:text-neutral-400">{s.type}</Td>
              <Td className="hidden md:table-cell font-mono text-xs text-gray-400 dark:text-neutral-500">{s.clusterIP ?? '—'}</Td>
              <Td className="font-mono text-xs text-gray-500 dark:text-neutral-400">{s.ports.join(', ') || '—'}</Td>
              <Td className="text-gray-400 dark:text-neutral-500 tabular-nums">{formatAge(s.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`Service · ${selected?.namespace ?? ns}`}
                    onClose={() => setSelected(null)}>
        {selected && ns && (
          <>
            <div className="pb-3">
              <ExplainPanel
                key={`${clusterId}/${selected.namespace}/${selected.name}`}
                clusterId={clusterId!}
                namespace={selected.namespace}
                kind="Service"
                name={selected.name}
              />
            </div>

            {canWrite && (
              <div className="pb-3 flex flex-wrap gap-2">
                <ForwardServiceButton clusterId={clusterId!} ns={selected.namespace} name={selected.name} />
                <EditYamlButton clusterId={clusterId!} ns={selected.namespace} kind="Service" name={selected.name} />
                <DeleteResourceButton
                  clusterId={clusterId!} ns={selected.namespace} kind="Service" name={selected.name}
                  onDeleted={() => { setSelected(null); queryClient.invalidateQueries({ queryKey: ['services', clusterId, ns] }) }}
                />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Type" value={selected.type} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Cluster IP" value={<span className="font-mono text-xs">{selected.clusterIP ?? '—'}</span>} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />

            <DrawerSection title="Ports" />
            {selected.ports.length === 0 ? (
              <p className="text-sm text-gray-400 dark:text-neutral-500">No ports.</p>
            ) : selected.ports.map((p, i) => (
              <p key={i} className="font-mono text-xs text-gray-700 dark:text-neutral-300 py-0.5">{p}</p>
            ))}

            {Object.keys(selected.selector).length > 0 && (
              <>
                <DrawerSection title="Selector" />
                {Object.entries(selected.selector).map(([k, v]) => (
                  <p key={k} className="font-mono text-xs text-gray-600 dark:text-neutral-400 py-0.5">{k}={v}</p>
                ))}
              </>
            )}
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
