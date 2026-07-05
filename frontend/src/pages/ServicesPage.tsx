import { useState } from 'react'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner, EmptyState } from '../components/ErrorBanner'
import { useNamespacedList, noNamespaceMessage } from '../hooks/useNamespacedList'
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
  const { ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<ServiceResource>('services')
  const [selected, setSelected] = useState<ServiceResource | null>(null)

  return (
    <Layout>
      <PageHeader title="Services" subtitle={ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={data?.length} noun="service" />

      {noNamespace && <EmptyState message={noNamespaceMessage('services')} />}
      {isLoading && <p className="text-sm text-gray-400">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load services: ${(error as Error).message}`} />}

      {data && (
        <Table columns={COLUMNS}>
          {data.map((s) => (
            <Tr key={s.name} onClick={() => setSelected(s)} highlighted={selected?.name === s.name}>
              <Td className="font-medium text-gray-900">{s.name}</Td>
              <Td className="text-gray-500">{s.type}</Td>
              <Td className="hidden md:table-cell font-mono text-xs text-gray-400">{s.clusterIP ?? '—'}</Td>
              <Td className="font-mono text-xs text-gray-500">{s.ports.join(', ') || '—'}</Td>
              <Td className="text-gray-400 tabular-nums">{formatAge(s.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`Service · ${ns}`}
                    onClose={() => setSelected(null)}>
        {selected && (
          <>
            <DrawerSection title="Overview" />
            <DrawerRow label="Type" value={selected.type} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Cluster IP" value={<span className="font-mono text-xs">{selected.clusterIP ?? '—'}</span>} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />

            <DrawerSection title="Ports" />
            {selected.ports.length === 0 ? (
              <p className="text-sm text-gray-400">No ports.</p>
            ) : selected.ports.map((p, i) => (
              <p key={i} className="font-mono text-xs text-gray-700 py-0.5">{p}</p>
            ))}

            {Object.keys(selected.selector).length > 0 && (
              <>
                <DrawerSection title="Selector" />
                {Object.entries(selected.selector).map(([k, v]) => (
                  <p key={k} className="font-mono text-xs text-gray-600 py-0.5">{k}={v}</p>
                ))}
              </>
            )}
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
