import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { Layout } from '../components/Layout'
import { DetailDrawer, DrawerRow } from '../components/DetailDrawer'
import { formatAge } from '../utils/format'
import type { K8sEvent } from '../types/k8s'

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
      <div className="flex items-center justify-between mb-4">
        <div>
          <h1 className="text-xl font-semibold text-gray-800">Events</h1>
          {ns && <p className="text-sm text-gray-500 mt-0.5">namespace: <span className="font-medium">{ns}</span></p>}
        </div>
        {data && (
          <span className="text-sm text-gray-500">{data.length} event{data.length !== 1 ? 's' : ''}</span>
        )}
      </div>

      {isLoading && <p className="text-gray-500 text-sm">Loading events…</p>}
      {isError && (
        <div className="text-sm text-red-600 bg-red-50 rounded-md px-4 py-3">
          Could not load events: {(error as Error).message}
        </div>
      )}

      {data && (
        <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white shadow-sm">
          <table className="w-full text-sm min-w-[640px]">
            <thead>
              <tr className="bg-gray-50 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide">
                <th className="px-4 py-3">Type</th>
                <th className="px-4 py-3">Reason</th>
                <th className="px-4 py-3">Object</th>
                <th className="px-4 py-3">Message</th>
                <th className="px-4 py-3 text-right">Count</th>
                <th className="px-4 py-3">Last seen</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {data.map((ev) => (
                <tr
                  key={ev.name}
                  onClick={() => setSelected(ev)}
                  className="hover:bg-blue-50 cursor-pointer transition-colors"
                >
                  <td className="px-4 py-3">
                    <span className={`text-xs font-semibold ${ev.type === 'Warning' ? 'text-red-600' : 'text-gray-500'}`}>
                      {ev.type}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-700">{ev.reason ?? '—'}</td>
                  <td className="px-4 py-3 text-gray-500 text-xs">
                    {ev.involvedObjectKind}/{ev.involvedObjectName}
                  </td>
                  <td className="px-4 py-3 text-gray-600 max-w-[320px] truncate">{ev.message ?? '—'}</td>
                  <td className="px-4 py-3 text-right text-gray-500">{ev.count}</td>
                  <td className="px-4 py-3 text-gray-500">{formatAge(ev.lastTimestamp)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <DetailDrawer
        open={selected !== null}
        title={`${selected?.reason ?? 'Event'}`}
        onClose={() => setSelected(null)}
      >
        {selected && (
          <>
            <DrawerRow label="Type" value={
              <span className={selected.type === 'Warning' ? 'text-red-600 font-semibold' : undefined}>
                {selected.type}
              </span>
            } />
            <DrawerRow label="Reason" value={selected.reason} />
            <DrawerRow label="Involved object" value={`${selected.involvedObjectKind}/${selected.involvedObjectName}`} />
            <DrawerRow label="Count" value={selected.count} />
            <DrawerRow label="First seen" value={formatAge(selected.firstTimestamp)} />
            <DrawerRow label="Last seen" value={formatAge(selected.lastTimestamp)} />
            <div>
              <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1">Message</p>
              <p className="text-sm text-gray-700 bg-gray-50 rounded-md px-3 py-2 whitespace-pre-wrap">{selected.message ?? '—'}</p>
            </div>
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
