import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { apiErrorMessage } from '../api/client'
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
import { useCanWrite } from '../auth/useCanWrite'
import { formatAge } from '../utils/format'
import type { ConfigMap } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'keys', label: 'Keys' },
  { key: 'age', label: 'Age' },
]

export function ConfigMapsPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<ConfigMap>('configmaps')
  const canWrite = useCanWrite(clusterId)
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
      {isLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load configmaps: ${apiErrorMessage(error)}`} />}

      {data && (
        <Table columns={withNamespaceColumn(COLUMNS, showNsColumn)} minWidth="420px">
          {data.map((cm) => (
            <Tr key={`${cm.namespace}/${cm.name}`} onClick={() => setSelected(cm)}
                highlighted={selected?.name === cm.name && selected?.namespace === cm.namespace}>
              <Td className="font-medium text-gray-900 dark:text-neutral-100">{cm.name}</Td>
              {showNsColumn && <Td className="text-gray-500 dark:text-neutral-400">{cm.namespace}</Td>}
              <Td className="text-gray-500 dark:text-neutral-400 tabular-nums">
                {Object.keys(cm.data).length}
                {cm.binaryDataCount > 0 && <span className="text-gray-400 dark:text-neutral-500"> (+{cm.binaryDataCount} binary)</span>}
              </Td>
              <Td className="text-gray-400 dark:text-neutral-500 tabular-nums">{formatAge(cm.creationTimestamp)}</Td>
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

            {canWrite && (
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
              <p className="text-sm text-gray-400 dark:text-neutral-500">No text data.</p>
            )}
            {Object.entries(selected.data).map(([key, value]) => (
              <div key={key} className="rounded-lg border border-gray-200 dark:border-neutral-700 overflow-hidden">
                <p className="px-3 py-1.5 bg-gray-50 dark:bg-neutral-800/60 border-b border-gray-200 dark:border-neutral-700 text-xs font-mono font-medium text-gray-700 dark:text-neutral-300">
                  {key}
                </p>
                <pre className="px-3 py-2 text-xs font-mono text-gray-600 dark:text-neutral-400 whitespace-pre-wrap break-all max-h-48 overflow-y-auto">
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
