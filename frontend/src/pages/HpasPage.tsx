import { useState } from 'react'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td, withNamespaceColumn } from '../components/Table'
import { StatusBadge } from '../components/StatusBadge'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner, EmptyState } from '../components/ErrorBanner'
import { useNamespacedList, noNamespaceMessage } from '../hooks/useNamespacedList'
import { CreateResourceButton } from '../components/CreateResourceButton'
import { HpaActions } from '../components/HpaActions'
import { ExplainPanel } from '../components/ExplainPanel'
import { useAuth } from '../auth/AuthContext'
import { formatAge } from '../utils/format'
import type { Hpa } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'target', label: 'Target' },
  { key: 'replicas', label: 'Replicas' },
  { key: 'cpu', label: 'CPU', className: 'hidden lg:table-cell' },
  { key: 'age', label: 'Age' },
]

function status(h: Hpa): string {
  if (h.currentReplicas >= h.maxReplicas) return 'Warning'
  if (h.currentReplicas > 0) return 'Ready'
  if (h.maxReplicas === 0) return 'Unknown'
  return 'Pending'
}

function cpuDisplay(h: Hpa): string {
  const current = h.currentCpuPercent != null ? `${h.currentCpuPercent}%` : '—'
  const target = h.targetCpuPercent != null ? `${h.targetCpuPercent}%` : '—'
  return `${current} / ${target}`
}

export function HpasPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<Hpa>('hpas')
  const { isAdmin } = useAuth()
  const [selected, setSelected] = useState<Hpa | null>(null)
  const showNsColumn = ns === 'all'

  return (
    <Layout>
      <PageHeader title="Horizontal Pod Autoscalers"
                  subtitle={ns === 'all' ? 'All namespaces' : ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={data?.length} noun="HPA"
                  actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="HorizontalPodAutoscaler" />} />

      {noNamespace && <EmptyState message={noNamespaceMessage('HPAs')} />}
      {isLoading && <p className="text-sm text-gray-400 dark:text-slate-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load HPAs: ${(error as Error).message}`} />}

      {data && (
        <Table columns={withNamespaceColumn(COLUMNS, showNsColumn)}>
          {data.map((h) => (
            <Tr key={`${h.namespace}/${h.name}`} onClick={() => setSelected(h)}
                highlighted={selected?.name === h.name && selected?.namespace === h.namespace}>
              <Td className="font-medium text-gray-900 dark:text-slate-100">{h.name}</Td>
              {showNsColumn && <Td className="text-gray-500 dark:text-slate-400">{h.namespace}</Td>}
              <Td><StatusBadge status={status(h)} /></Td>
              <Td className="font-mono text-xs text-gray-500 dark:text-slate-400">{h.targetRef}</Td>
              <Td className="tabular-nums text-gray-500 dark:text-slate-400">
                {h.currentReplicas} / {h.minReplicas}–{h.maxReplicas}
              </Td>
              <Td className="hidden lg:table-cell tabular-nums text-gray-400 dark:text-slate-500">
                {cpuDisplay(h)}
              </Td>
              <Td className="text-gray-400 dark:text-slate-500 tabular-nums">{formatAge(h.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`HPA · ${selected?.namespace ?? ns}`}
                    onClose={() => setSelected(null)}>
        {selected && ns && (
          <>
            <div className="pb-3">
              <ExplainPanel
                key={`${clusterId}/${selected.namespace}/${selected.name}`}
                clusterId={clusterId!}
                namespace={selected.namespace}
                kind="HorizontalPodAutoscaler"
                name={selected.name}
              />
            </div>

            {isAdmin && (
              <div className="pb-3">
                <HpaActions
                  key={`actions-${clusterId}/${selected.namespace}/${selected.name}`}
                  clusterId={clusterId!}
                  namespace={selected.namespace}
                  hpa={selected}
                  onActionDone={() => setSelected(null)}
                />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Status" value={<StatusBadge status={status(selected)} />} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Target" value={<span className="font-mono text-xs">{selected.targetRef}</span>} />
            <DrawerRow label="Replicas" value={`${selected.currentReplicas} current / ${selected.minReplicas} min / ${selected.maxReplicas} max`} />
            <DrawerRow label="CPU utilization" value={cpuDisplay(selected)} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
