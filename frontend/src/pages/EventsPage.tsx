import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner } from '../components/ErrorBanner'
import { formatAge } from '../utils/format'
import type { K8sEvent } from '../types/k8s'

const COLUMNS = [
  { key: 'type', label: 'Type' },
  { key: 'reason', label: 'Reason' },
  { key: 'object', label: 'Object' },
  { key: 'message', label: 'Message' },
  { key: 'count', label: 'Count', className: 'text-right' },
  { key: 'age', label: 'Last seen' },
]

const PAGE_SIZE = 10

export function EventsPage() {
  const { clusterId, ns } = useParams<{ clusterId: string; ns: string }>()
  const [selected, setSelected] = useState<K8sEvent | null>(null)
  const [page, setPage] = useState(0)

  const noNamespace = ns === '_'
  const { data, isLoading, isError, error } = useQuery<K8sEvent[]>({
    queryKey: ['events', clusterId, ns],
    queryFn: async () => (await api.get<K8sEvent[]>(`/clusters/${clusterId}/namespaces/${ns}/events`)).data,
    enabled: !!clusterId && !!ns && !noNamespace,
    refetchInterval: 15_000,
  })

  // Events are fetched and sorted whole (K8s continue-tokens can't preserve the
  // sort across pages), so paginate client-side.
  const totalPages = data ? Math.max(1, Math.ceil(data.length / PAGE_SIZE)) : 1
  const safePage = Math.min(page, totalPages - 1)
  const pageRows = data ? data.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE) : []

  return (
    <Layout>
      <PageHeader
        title="Events"
        subtitle={ns ? `namespace: ${ns}` : undefined}
        count={data?.length}
        noun="event"
      />

      {noNamespace && (
        <div className="rounded-xl border border-dashed border-gray-200 dark:border-slate-700 bg-white dark:bg-slate-900 px-6 py-12 text-center">
          <p className="text-sm text-gray-400 dark:text-slate-500">Select a namespace from the sidebar to view events.</p>
        </div>
      )}
      {isLoading && <p className="text-sm text-gray-400 dark:text-slate-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load events: ${(error as Error).message}`} />}

      {data && data.length === 0 && (
        <div className="rounded-lg border border-dashed border-gray-200 dark:border-slate-700 bg-white dark:bg-slate-900 px-6 py-12 text-center">
          <p className="text-sm text-gray-400 dark:text-slate-500">No events in this namespace.</p>
        </div>
      )}

      {data && data.length > 0 && (
        <>
          <Table columns={COLUMNS}>
            {pageRows.map((ev) => (
              <Tr
                key={ev.name}
                onClick={() => setSelected(ev)}
                highlighted={selected?.name === ev.name}
              >
                <Td>
                  <span className={`text-xs font-semibold ${ev.type === 'Warning' ? 'text-red-600 dark:text-red-400' : 'text-gray-400 dark:text-slate-500'}`}>
                    {ev.type}
                  </span>
                </Td>
                <Td className="text-gray-700 dark:text-slate-300 whitespace-nowrap">{ev.reason ?? '—'}</Td>
                <Td className="text-xs text-gray-400 dark:text-slate-500 whitespace-nowrap">
                  {ev.involvedObjectKind}/{ev.involvedObjectName}
                </Td>
                <Td className="text-gray-600 dark:text-slate-400 max-w-xs truncate">{ev.message ?? '—'}</Td>
                <Td className="text-right tabular-nums text-gray-400 dark:text-slate-500">{ev.count}</Td>
                <Td className="text-gray-400 dark:text-slate-500 tabular-nums whitespace-nowrap">{formatAge(ev.lastTimestamp)}</Td>
              </Tr>
            ))}
          </Table>

          {totalPages > 1 && (
            <div className="flex items-center justify-between mt-4">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={safePage === 0}
                className="rounded-md border border-gray-300 dark:border-slate-600 px-3 py-1.5 text-xs text-gray-600 dark:text-slate-400
                           hover:bg-gray-50 dark:hover:bg-slate-800 disabled:opacity-40 transition-colors"
              >
                Previous
              </button>
              <span className="text-xs text-gray-400 dark:text-slate-500">Page {safePage + 1} of {totalPages}</span>
              <button
                onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                disabled={safePage + 1 >= totalPages}
                className="rounded-md border border-gray-300 dark:border-slate-600 px-3 py-1.5 text-xs text-gray-600 dark:text-slate-400
                           hover:bg-gray-50 dark:hover:bg-slate-800 disabled:opacity-40 transition-colors"
              >
                Next
              </button>
            </div>
          )}
        </>
      )}

      <DetailDrawer
        open={!!selected}
        title={selected?.reason ?? 'Event'}
        subtitle={`${selected?.type} · ${selected?.involvedObjectKind}/${selected?.involvedObjectName}`}
        onClose={() => setSelected(null)}
      >
        {selected && (
          <>
            <DrawerSection title="Event" />
            <DrawerRow label="Type" value={
              <span className={selected.type === 'Warning' ? 'font-semibold text-red-600 dark:text-red-400' : 'text-gray-600 dark:text-slate-400'}>
                {selected.type}
              </span>
            } />
            <DrawerRow label="Reason" value={selected.reason} />
            <DrawerRow label="Count" value={selected.count} />
            <DrawerRow label="First seen" value={formatAge(selected.firstTimestamp)} />
            <DrawerRow label="Last seen" value={formatAge(selected.lastTimestamp)} />

            <DrawerSection title="Involved object" />
            <DrawerRow label="Kind" value={selected.involvedObjectKind} />
            <DrawerRow label="Name" value={selected.involvedObjectName} />

            <DrawerSection title="Message" />
            <div className="rounded-md bg-gray-50 dark:bg-slate-800/60 border border-gray-200 dark:border-slate-700 px-3 py-2.5">
              <p className="text-sm text-gray-700 dark:text-slate-300 whitespace-pre-wrap leading-relaxed">
                {selected.message ?? '—'}
              </p>
            </div>
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
