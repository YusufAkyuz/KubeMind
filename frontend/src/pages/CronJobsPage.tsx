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
import type { CronJob } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'schedule', label: 'Schedule' },
  { key: 'status', label: 'Status' },
  { key: 'active', label: 'Active' },
  { key: 'image', label: 'Image', className: 'hidden lg:table-cell' },
  { key: 'age', label: 'Age' },
]

export function CronJobsPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<CronJob>('cronjobs')
  const { isAdmin } = useAuth()
  const [selected, setSelected] = useState<CronJob | null>(null)
  const queryClient = useQueryClient()

  return (
    <Layout>
      <PageHeader title="CronJobs" subtitle={ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={data?.length} noun="cronjob"
                  actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="CronJob" />} />

      {noNamespace && <EmptyState message={noNamespaceMessage('cronjobs')} />}
      {isLoading && <p className="text-sm text-gray-400">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load cronjobs: ${(error as Error).message}`} />}

      {data && (
        <Table columns={COLUMNS}>
          {data.map((c) => (
            <Tr key={c.name} onClick={() => setSelected(c)} highlighted={selected?.name === c.name}>
              <Td className="font-medium text-gray-900">{c.name}</Td>
              <Td className="font-mono text-xs text-gray-500">{c.schedule ?? '—'}</Td>
              <Td><StatusBadge status={c.suspended ? 'Pending' : 'Ready'} /></Td>
              <Td className="tabular-nums text-gray-500">{c.activeJobs}</Td>
              <Td className="hidden lg:table-cell font-mono text-xs text-gray-400 max-w-[240px] truncate">
                {c.image ?? '—'}
              </Td>
              <Td className="text-gray-400 tabular-nums">{formatAge(c.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`CronJob · ${ns}`}
                    onClose={() => setSelected(null)}>
        {selected && ns && (
          <>
            <div className="pb-3">
              <ExplainPanel
                key={`${clusterId}/${ns}/${selected.name}`}
                clusterId={clusterId!}
                namespace={ns}
                kind="CronJob"
                name={selected.name}
              />
            </div>

            {isAdmin && (
              <div className="pb-3 flex flex-wrap gap-2">
                <EditYamlButton clusterId={clusterId!} ns={ns} kind="CronJob" name={selected.name} />
                <DeleteResourceButton
                  clusterId={clusterId!} ns={ns} kind="CronJob" name={selected.name}
                  onDeleted={() => { setSelected(null); queryClient.invalidateQueries({ queryKey: ['cronjobs', clusterId, ns] }) }}
                />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Status" value={<StatusBadge status={selected.suspended ? 'Pending' : 'Ready'} />} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Schedule" value={<span className="font-mono text-xs">{selected.schedule}</span>} />
            <DrawerRow label="Suspended" value={selected.suspended ? 'Yes' : 'No'} />
            <DrawerRow label="Active jobs" value={selected.activeJobs} />
            <DrawerRow label="Last scheduled" value={formatAge(selected.lastScheduleTime)} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />

            <DrawerSection title="Image" />
            <DrawerRow label="Container 0" value={
              <span className="font-mono text-xs break-all">{selected.image ?? '—'}</span>
            } />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
