import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { StatusBadge } from '../components/StatusBadge'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner, EmptyState } from '../components/ErrorBanner'
import { useNamespacedList, noNamespaceMessage } from '../hooks/useNamespacedList'
import { CreateResourceButton } from '../components/CreateResourceButton'
import { EditYamlButton } from '../components/EditYamlButton'
import { DeleteResourceButton } from '../components/DeleteResourceButton'
import { ExplainPanel } from '../components/ExplainPanel'
import { useAuth } from '../auth/AuthContext'
import { formatAge } from '../utils/format'
import type { Pvc } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'capacity', label: 'Capacity' },
  { key: 'storageClass', label: 'Storage class', className: 'hidden md:table-cell' },
  { key: 'age', label: 'Age' },
]

// PVC phase → StatusBadge vocabulary (Bound is the healthy state).
const PHASE: Record<string, string> = { Bound: 'Ready', Pending: 'Pending', Lost: 'Failed' }

export function PvcsPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } =
    useNamespacedList<Pvc>('persistentvolumeclaims')
  const { isAdmin } = useAuth()
  const [selected, setSelected] = useState<Pvc | null>(null)
  const queryClient = useQueryClient()

  return (
    <Layout>
      <PageHeader title="Persistent Volume Claims" subtitle={ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={data?.length} noun="claim"
                  actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="PersistentVolumeClaim" />} />

      {noNamespace && <EmptyState message={noNamespaceMessage('persistent volume claims')} />}
      {isLoading && <p className="text-sm text-gray-400">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load claims: ${(error as Error).message}`} />}

      {data && (
        <Table columns={COLUMNS}>
          {data.map((pvc) => (
            <Tr key={pvc.name} onClick={() => setSelected(pvc)} highlighted={selected?.name === pvc.name}>
              <Td className="font-medium text-gray-900">{pvc.name}</Td>
              <Td><StatusBadge status={PHASE[pvc.status] ?? 'Unknown'} /></Td>
              <Td className="text-gray-600 tabular-nums">{pvc.capacity ?? '—'}</Td>
              <Td className="hidden md:table-cell text-gray-500">{pvc.storageClass ?? '—'}</Td>
              <Td className="text-gray-400 tabular-nums">{formatAge(pvc.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`PVC · ${ns}`}
                    onClose={() => setSelected(null)}>
        {selected && ns && (
          <>
            <div className="pb-3">
              <ExplainPanel
                key={`${clusterId}/${ns}/${selected.name}`}
                clusterId={clusterId!}
                namespace={ns}
                kind="PersistentVolumeClaim"
                name={selected.name}
              />
            </div>

            {isAdmin && (
              <div className="pb-3 flex flex-wrap gap-2">
                <EditYamlButton clusterId={clusterId!} ns={ns} kind="PersistentVolumeClaim" name={selected.name} />
                <DeleteResourceButton
                  clusterId={clusterId!} ns={ns} kind="PersistentVolumeClaim" name={selected.name}
                  onDeleted={() => { setSelected(null); queryClient.invalidateQueries({ queryKey: ['persistentvolumeclaims', clusterId, ns] }) }}
                />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Status" value={<StatusBadge status={PHASE[selected.status] ?? 'Unknown'} />} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Capacity" value={selected.capacity} />
            <DrawerRow label="Storage class" value={selected.storageClass} />
            <DrawerRow label="Access modes" value={selected.accessModes.join(', ') || '—'} />
            <DrawerRow label="Volume" value={<span className="font-mono text-xs break-all">{selected.volumeName ?? '—'}</span>} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
