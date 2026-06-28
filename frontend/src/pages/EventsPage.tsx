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

export function EventsPage() {
  const { ns } = useParams<{ ns: string }>()
  const [selected, setSelected] = useState<K8sEvent | null>(null)

  const { data, isLoading, isError, error } = useQuery<K8sEvent[]>({
    queryKey: ['events', ns],
    queryFn: async () => (await api.get<K8sEvent[]>(`/k8s/namespaces/${ns}/events`)).data,
    enabled: !!ns,
    refetchInterval: 15_000,
  })

  return (
    <Layout>
      <PageHeader
        title="Events"
        subtitle={ns ? `namespace: ${ns}` : undefined}
        count={data?.length}
        noun="event"
      />

      {isLoading && <p className="text-sm text-gray-400">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load events: ${(error as Error).message}`} />}

      {data && (
        <Table columns={COLUMNS}>
          {data.map((ev) => (
            <Tr
              key={ev.name}
              onClick={() => setSelected(ev)}
              highlighted={selected?.name === ev.name}
            >
              <Td>
                <span className={`text-xs font-semibold ${ev.type === 'Warning' ? 'text-red-600' : 'text-gray-400'}`}>
                  {ev.type}
                </span>
              </Td>
              <Td className="text-gray-700 whitespace-nowrap">{ev.reason ?? '—'}</Td>
              <Td className="text-xs text-gray-400 whitespace-nowrap">
                {ev.involvedObjectKind}/{ev.involvedObjectName}
              </Td>
              <Td className="text-gray-600 max-w-xs truncate">{ev.message ?? '—'}</Td>
              <Td className="text-right tabular-nums text-gray-400">{ev.count}</Td>
              <Td className="text-gray-400 tabular-nums whitespace-nowrap">{formatAge(ev.lastTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
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
              <span className={selected.type === 'Warning' ? 'font-semibold text-red-600' : 'text-gray-600'}>
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
            <div className="rounded-md bg-gray-50 border border-gray-200 px-3 py-2.5">
              <p className="text-sm text-gray-700 whitespace-pre-wrap leading-relaxed">
                {selected.message ?? '—'}
              </p>
            </div>
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
