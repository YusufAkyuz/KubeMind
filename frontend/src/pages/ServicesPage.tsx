import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner, EmptyState } from '../components/ErrorBanner'
import { useNamespacedList, noNamespaceMessage } from '../hooks/useNamespacedList'
import { CreateResourceButton } from '../components/CreateResourceButton'
import { EditYamlButton } from '../components/EditYamlButton'
import { DeleteResourceButton } from '../components/DeleteResourceButton'
import { ExplainPanel } from '../components/ExplainPanel'
import { useAuth } from '../auth/AuthContext'
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
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<ServiceResource>('services')
  const { isAdmin } = useAuth()
  const [selected, setSelected] = useState<ServiceResource | null>(null)
  const queryClient = useQueryClient()

  return (
    <Layout>
      <PageHeader title="Services" subtitle={ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={data?.length} noun="service"
                  actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="Service" />} />

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
        {selected && ns && (
          <>
            <div className="pb-3">
              <ExplainPanel
                key={`${clusterId}/${ns}/${selected.name}`}
                clusterId={clusterId!}
                namespace={ns}
                kind="Service"
                name={selected.name}
              />
            </div>

            {isAdmin && (
              <div className="pb-3 flex flex-wrap gap-2">
                <EditYamlButton clusterId={clusterId!} ns={ns} kind="Service" name={selected.name} />
                <DeleteResourceButton
                  clusterId={clusterId!} ns={ns} kind="Service" name={selected.name}
                  onDeleted={() => { setSelected(null); queryClient.invalidateQueries({ queryKey: ['services', clusterId, ns] }) }}
                />
              </div>
            )}

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
