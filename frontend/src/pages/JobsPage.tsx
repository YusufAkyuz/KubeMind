import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td, withNamespaceColumn } from '../components/Table'
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
import type { Job } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'completions', label: 'Completions' },
  { key: 'image', label: 'Image', className: 'hidden lg:table-cell' },
  { key: 'age', label: 'Age' },
]

export function JobsPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<Job>('jobs')
  const { isAdmin } = useAuth()
  const [selected, setSelected] = useState<Job | null>(null)
  const queryClient = useQueryClient()
  const showNsColumn = ns === 'all'

  return (
    <Layout>
      <PageHeader title="Jobs"
                  subtitle={ns === 'all' ? 'All namespaces' : ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={data?.length} noun="job"
                  actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="Job" />} />

      {noNamespace && <EmptyState message={noNamespaceMessage('jobs')} />}
      {isLoading && <p className="text-sm text-gray-400 dark:text-slate-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load jobs: ${(error as Error).message}`} />}

      {data && (
        <Table columns={withNamespaceColumn(COLUMNS, showNsColumn)}>
          {data.map((j) => (
            <Tr key={`${j.namespace}/${j.name}`} onClick={() => setSelected(j)}
                highlighted={selected?.name === j.name && selected?.namespace === j.namespace}>
              <Td className="font-medium text-gray-900 dark:text-slate-100">{j.name}</Td>
              {showNsColumn && <Td className="text-gray-500 dark:text-slate-400">{j.namespace}</Td>}
              <Td><StatusBadge status={j.status} /></Td>
              <Td className="tabular-nums text-gray-500 dark:text-slate-400">
                {j.succeeded}/{j.completions ?? '—'}
                {j.failed > 0 && <span className="text-red-600 dark:text-red-400 ml-1">({j.failed} failed)</span>}
              </Td>
              <Td className="hidden lg:table-cell font-mono text-xs text-gray-400 dark:text-slate-500 max-w-[240px] truncate">
                {j.image ?? '—'}
              </Td>
              <Td className="text-gray-400 dark:text-slate-500 tabular-nums">{formatAge(j.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`Job · ${selected?.namespace ?? ns}`}
                    onClose={() => setSelected(null)}>
        {selected && ns && (
          <>
            <div className="pb-3">
              <ExplainPanel
                key={`${clusterId}/${selected.namespace}/${selected.name}`}
                clusterId={clusterId!}
                namespace={selected.namespace}
                kind="Job"
                name={selected.name}
              />
            </div>

            {isAdmin && (
              <div className="pb-3 flex flex-wrap gap-2">
                <EditYamlButton clusterId={clusterId!} ns={selected.namespace} kind="Job" name={selected.name} />
                <DeleteResourceButton
                  clusterId={clusterId!} ns={selected.namespace} kind="Job" name={selected.name}
                  onDeleted={() => { setSelected(null); queryClient.invalidateQueries({ queryKey: ['jobs', clusterId, ns] }) }}
                />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Status" value={<StatusBadge status={selected.status} />} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Completions" value={`${selected.succeeded} succeeded / ${selected.completions ?? '—'} desired`} />
            <DrawerRow label="Active" value={selected.active} />
            <DrawerRow label="Failed" value={selected.failed} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />

            <DrawerSection title="Timing" />
            <DrawerRow label="Started" value={formatAge(selected.startTime)} />
            <DrawerRow label="Completed" value={selected.completionTime ? formatAge(selected.completionTime) : '—'} />

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
