import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td, withNamespaceColumn } from '../components/Table'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner, EmptyState } from '../components/ErrorBanner'
import { useNamespacedList, noNamespaceMessage } from '../hooks/useNamespacedList'
import { CreateResourceButton } from '../components/CreateResourceButton'
import { EditYamlButton } from '../components/EditYamlButton'
import { DeleteResourceButton } from '../components/DeleteResourceButton'
import { ExplainPanel } from '../components/ExplainPanel'
import { useAuth } from '../auth/AuthContext'
import { formatAge } from '../utils/format'
import type { ConfigMap } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'keys', label: 'Keys' },
  { key: 'age', label: 'Age' },
]

export function ConfigMapsPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<ConfigMap>('configmaps')
  const { isAdmin } = useAuth()
  const [selected, setSelected] = useState<ConfigMap | null>(null)
  const queryClient = useQueryClient()
  const showNsColumn = ns === 'all'

  return (
    <Layout>
      <PageHeader title="ConfigMaps"
                  subtitle={ns === 'all' ? 'All namespaces' : ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={data?.length} noun="configmap"
                  actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="ConfigMap" />} />

      {noNamespace && <EmptyState message={noNamespaceMessage('configmaps')} />}
      {isLoading && <p className="text-sm text-gray-400">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load configmaps: ${(error as Error).message}`} />}

      {data && (
        <Table columns={withNamespaceColumn(COLUMNS, showNsColumn)} minWidth="420px">
          {data.map((cm) => (
            <Tr key={`${cm.namespace}/${cm.name}`} onClick={() => setSelected(cm)}
                highlighted={selected?.name === cm.name && selected?.namespace === cm.namespace}>
              <Td className="font-medium text-gray-900">{cm.name}</Td>
              {showNsColumn && <Td className="text-gray-500">{cm.namespace}</Td>}
              <Td className="text-gray-500 tabular-nums">
                {Object.keys(cm.data).length}
                {cm.binaryDataCount > 0 && <span className="text-gray-400"> (+{cm.binaryDataCount} binary)</span>}
              </Td>
              <Td className="text-gray-400 tabular-nums">{formatAge(cm.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`ConfigMap · ${selected?.namespace ?? ns}`}
                    onClose={() => setSelected(null)}>
        {selected && ns && (
          <>
            <div className="pb-3">
              <ExplainPanel
                key={`${clusterId}/${selected.namespace}/${selected.name}`}
                clusterId={clusterId!}
                namespace={selected.namespace}
                kind="ConfigMap"
                name={selected.name}
              />
            </div>

            {isAdmin && (
              <div className="pb-3 flex flex-wrap gap-2">
                <EditYamlButton clusterId={clusterId!} ns={selected.namespace} kind="ConfigMap" name={selected.name} />
                <DeleteResourceButton
                  clusterId={clusterId!} ns={selected.namespace} kind="ConfigMap" name={selected.name}
                  onDeleted={() => { setSelected(null); queryClient.invalidateQueries({ queryKey: ['configmaps', clusterId, ns] }) }}
                />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />

            <DrawerSection title={`Data (${Object.keys(selected.data).length})`} />
            {Object.keys(selected.data).length === 0 && (
              <p className="text-sm text-gray-400">No text data.</p>
            )}
            {Object.entries(selected.data).map(([key, value]) => (
              <div key={key} className="rounded-lg border border-gray-200 overflow-hidden">
                <p className="px-3 py-1.5 bg-gray-50 border-b border-gray-200 text-xs font-mono font-medium text-gray-700">
                  {key}
                </p>
                <pre className="px-3 py-2 text-xs font-mono text-gray-600 whitespace-pre-wrap break-all max-h-48 overflow-y-auto">
                  {value}
                </pre>
              </div>
            ))}
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
