import { useState } from 'react'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td, withNamespaceColumn } from '../components/Table'
import { StatusBadge } from '../components/StatusBadge'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner, EmptyState } from '../components/ErrorBanner'
import { useNamespacedList, noNamespaceMessage } from '../hooks/useNamespacedList'
import { CreateResourceButton } from '../components/CreateResourceButton'
import { DaemonSetActions } from '../components/DaemonSetActions'
import { ExplainPanel } from '../components/ExplainPanel'
import { useCanWrite } from '../auth/useCanWrite'
import { formatAge } from '../utils/format'
import type { DaemonSet } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'ready', label: 'Ready' },
  { key: 'image', label: 'Image', className: 'hidden lg:table-cell' },
  { key: 'age', label: 'Age' },
]

function status(d: DaemonSet): string {
  if (d.desired === 0) return 'Unknown'
  if (d.ready === d.desired) return 'Ready'
  if (d.ready === 0) return 'Failed'
  return 'Pending'
}

export function DaemonSetsPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<DaemonSet>('daemonsets')
  const canWrite = useCanWrite(clusterId)
  const [selected, setSelected] = useState<DaemonSet | null>(null)
  const showNsColumn = ns === 'all'

  return (
    <Layout>
      <PageHeader title="DaemonSets"
                  subtitle={ns === 'all' ? 'All namespaces' : ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={data?.length} noun="daemonset"
                  actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="DaemonSet" />} />

      {noNamespace && <EmptyState message={noNamespaceMessage('daemonsets')} />}
      {isLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load daemonsets: ${(error as Error).message}`} />}

      {data && (
        <Table columns={withNamespaceColumn(COLUMNS, showNsColumn)}>
          {data.map((d) => (
            <Tr key={`${d.namespace}/${d.name}`} onClick={() => setSelected(d)}
                highlighted={selected?.name === d.name && selected?.namespace === d.namespace}>
              <Td className="font-medium text-gray-900 dark:text-neutral-100">{d.name}</Td>
              {showNsColumn && <Td className="text-gray-500 dark:text-neutral-400">{d.namespace}</Td>}
              <Td><StatusBadge status={status(d)} /></Td>
              <Td className="tabular-nums text-gray-500 dark:text-neutral-400">{d.ready}/{d.desired}</Td>
              <Td className="hidden lg:table-cell font-mono text-xs text-gray-400 dark:text-neutral-500 max-w-[240px] truncate">
                {d.image ?? '—'}
              </Td>
              <Td className="text-gray-400 dark:text-neutral-500 tabular-nums">{formatAge(d.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`DaemonSet · ${selected?.namespace ?? ns}`}
                    onClose={() => setSelected(null)}>
        {selected && ns && (
          <>
            <div className="pb-3">
              <ExplainPanel
                key={`${clusterId}/${selected.namespace}/${selected.name}`}
                clusterId={clusterId!}
                namespace={selected.namespace}
                kind="DaemonSet"
                name={selected.name}
              />
            </div>

            {canWrite && (
              <div className="pb-3">
                <DaemonSetActions
                  key={`actions-${clusterId}/${selected.namespace}/${selected.name}`}
                  clusterId={clusterId!}
                  namespace={selected.namespace}
                  daemonSet={selected}
                  onActionDone={() => setSelected(null)}
                />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Status" value={<StatusBadge status={status(selected)} />} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Pods" value={`${selected.ready} ready / ${selected.available} available / ${selected.desired} desired`} />
            <DrawerRow label="Image" value={<span className="font-mono text-xs break-all">{selected.image ?? '—'}</span>} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
