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
import type { Ingress } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'class', label: 'Class' },
  { key: 'hosts', label: 'Hosts' },
  { key: 'age', label: 'Age' },
]

export function IngressesPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<Ingress>('ingresses')
  const { isAdmin } = useAuth()
  const [selected, setSelected] = useState<Ingress | null>(null)
  const queryClient = useQueryClient()

  return (
    <Layout>
      <PageHeader title="Ingresses" subtitle={ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={data?.length} noun="ingress"
                  actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="Ingress" />} />

      {noNamespace && <EmptyState message={noNamespaceMessage('ingresses')} />}
      {isLoading && <p className="text-sm text-gray-400">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load ingresses: ${(error as Error).message}`} />}

      {data && (
        <Table columns={COLUMNS}>
          {data.map((ing) => {
            const hosts = [...new Set(ing.rules.map((r) => r.host))].join(', ')
            return (
              <Tr key={ing.name} onClick={() => setSelected(ing)} highlighted={selected?.name === ing.name}>
                <Td className="font-medium text-gray-900">{ing.name}</Td>
                <Td className="text-gray-500">{ing.className ?? '—'}</Td>
                <Td className="text-gray-600 max-w-xs truncate">{hosts || '—'}</Td>
                <Td className="text-gray-400 tabular-nums">{formatAge(ing.creationTimestamp)}</Td>
              </Tr>
            )
          })}
        </Table>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`Ingress · ${ns}`}
                    onClose={() => setSelected(null)}>
        {selected && ns && (
          <>
            <div className="pb-3">
              <ExplainPanel
                key={`${clusterId}/${ns}/${selected.name}`}
                clusterId={clusterId!}
                namespace={ns}
                kind="Ingress"
                name={selected.name}
              />
            </div>

            {isAdmin && (
              <div className="pb-3 flex flex-wrap gap-2">
                <EditYamlButton clusterId={clusterId!} ns={ns} kind="Ingress" name={selected.name} />
                <DeleteResourceButton
                  clusterId={clusterId!} ns={ns} kind="Ingress" name={selected.name}
                  onDeleted={() => { setSelected(null); queryClient.invalidateQueries({ queryKey: ['ingresses', clusterId, ns] }) }}
                />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Class" value={selected.className} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />

            <DrawerSection title="Rules" />
            {selected.rules.length === 0 ? (
              <p className="text-sm text-gray-400">No rules.</p>
            ) : selected.rules.map((r, i) => (
              <div key={i} className="rounded-lg border border-gray-200 px-3 py-2 text-xs font-mono space-y-0.5">
                <p className="text-gray-800">{r.host}<span className="text-gray-400">{r.path}</span></p>
                <p className="text-gray-500">→ {r.backend}</p>
              </div>
            ))}
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
