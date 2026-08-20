import { useState } from 'react'
import { apiErrorMessage } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td, withNamespaceColumn } from '../components/Table'
import { StatusBadge } from '../components/StatusBadge'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner, EmptyState } from '../components/ErrorBanner'
import { useNamespacedList, noNamespaceMessage } from '../hooks/useNamespacedList'
import { CreateResourceButton } from '../components/CreateResourceButton'
import { StatefulSetActions } from '../components/StatefulSetActions'
import { ExplainPanel } from '../components/ExplainPanel'
import { useCanWrite } from '../auth/useCanWrite'
import { formatAge } from '../utils/format'
import type { StatefulSet } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'ready', label: 'Ready' },
  { key: 'image', label: 'Image', className: 'hidden lg:table-cell' },
  { key: 'age', label: 'Age' },
]

function status(s: StatefulSet): string {
  if (s.desiredReplicas === 0) return 'Unknown'
  if (s.readyReplicas === s.desiredReplicas) return 'Ready'
  if (s.readyReplicas === 0) return 'Failed'
  return 'Pending'
}

export function StatefulSetsPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<StatefulSet>('statefulsets')
  const canWrite = useCanWrite(clusterId)
  const [selected, setSelected] = useState<StatefulSet | null>(null)
  const showNsColumn = ns === 'all'

  return (
    <Layout>
      <PageHeader title="StatefulSets"
                  subtitle={ns === 'all' ? 'All namespaces' : ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={data?.length} noun="statefulset"
                  actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="StatefulSet" />} />

      {noNamespace && <EmptyState message={noNamespaceMessage('statefulsets')} />}
      {isLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load statefulsets: ${apiErrorMessage(error)}`} />}

      {data && (
        <Table columns={withNamespaceColumn(COLUMNS, showNsColumn)}>
          {data.map((s) => (
            <Tr key={`${s.namespace}/${s.name}`} onClick={() => setSelected(s)}
                highlighted={selected?.name === s.name && selected?.namespace === s.namespace}>
              <Td className="font-medium text-gray-900 dark:text-neutral-100">{s.name}</Td>
              {showNsColumn && <Td className="text-gray-500 dark:text-neutral-400">{s.namespace}</Td>}
              <Td><StatusBadge status={status(s)} /></Td>
              <Td className="tabular-nums text-gray-500 dark:text-neutral-400">{s.readyReplicas}/{s.desiredReplicas}</Td>
              <Td className="hidden lg:table-cell font-mono text-xs text-gray-400 dark:text-neutral-500 max-w-[240px] truncate">
                {s.image ?? '—'}
              </Td>
              <Td className="text-gray-400 dark:text-neutral-500 tabular-nums">{formatAge(s.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`StatefulSet · ${selected?.namespace ?? ns}`}
                    onClose={() => setSelected(null)}>
        {selected && ns && (
          <>
            <div className="pb-3">
              <ExplainPanel
                key={`${clusterId}/${selected.namespace}/${selected.name}`}
                clusterId={clusterId!}
                namespace={selected.namespace}
                kind="StatefulSet"
                name={selected.name}
              />
            </div>

            {canWrite && (
              <div className="pb-3">
                <StatefulSetActions
                  key={`actions-${clusterId}/${selected.namespace}/${selected.name}`}
                  clusterId={clusterId!}
                  namespace={selected.namespace}
                  statefulSet={selected}
                  onActionDone={() => setSelected(null)}
                />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Status" value={<StatusBadge status={status(selected)} />} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Replicas" value={`${selected.readyReplicas} ready / ${selected.desiredReplicas} desired`} />
            <DrawerRow label="Headless service" value={selected.serviceName} />
            <DrawerRow label="Image" value={<span className="font-mono text-xs break-all">{selected.image ?? '—'}</span>} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
