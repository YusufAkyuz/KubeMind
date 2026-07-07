import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, apiErrorMessage } from '../api/client'
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
import type { Secret } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'type', label: 'Type' },
  { key: 'keys', label: 'Keys' },
  { key: 'age', label: 'Age' },
]

export function SecretsPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<Secret>('secrets')
  const { isAdmin } = useAuth()
  const [selected, setSelected] = useState<Secret | null>(null)
  const queryClient = useQueryClient()

  const showNsColumn = ns === 'all'

  const reveal = useMutation<Record<string, string>, unknown>({
    mutationFn: async () =>
      (await api.get<Record<string, string>>(
        `/clusters/${clusterId}/namespaces/${selected?.namespace}/secrets/${selected?.name}/reveal`)).data,
  })

  const select = (s: Secret) => {
    reveal.reset() // never carry revealed values from one secret to another
    setSelected(s)
  }

  return (
    <Layout>
      <PageHeader title="Secrets"
                  subtitle={ns === 'all' ? 'All namespaces' : ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={data?.length} noun="secret"
                  actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="Secret" />} />

      {noNamespace && <EmptyState message={noNamespaceMessage('secrets')} />}
      {isLoading && <p className="text-sm text-gray-400">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load secrets: ${(error as Error).message}`} />}

      {data && (
        <Table columns={withNamespaceColumn(COLUMNS, showNsColumn)} minWidth="480px">
          {data.map((s) => (
            <Tr key={`${s.namespace}/${s.name}`} onClick={() => select(s)}
                highlighted={selected?.name === s.name && selected?.namespace === s.namespace}>
              <Td className="font-medium text-gray-900">{s.name}</Td>
              {showNsColumn && <Td className="text-gray-500">{s.namespace}</Td>}
              <Td className="font-mono text-xs text-gray-500">{s.type}</Td>
              <Td className="text-gray-500 tabular-nums">{s.keys.length}</Td>
              <Td className="text-gray-400 tabular-nums">{formatAge(s.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`Secret · ${selected?.namespace ?? ns}`}
                    onClose={() => { setSelected(null); reveal.reset() }}>
        {selected && ns && (
          <>
            <div className="pb-3">
              <ExplainPanel
                key={`${clusterId}/${selected.namespace}/${selected.name}`}
                clusterId={clusterId!}
                namespace={selected.namespace}
                kind="Secret"
                name={selected.name}
              />
            </div>

            {isAdmin && (
              <div className="pb-3 flex flex-wrap gap-2">
                <EditYamlButton clusterId={clusterId!} ns={selected.namespace} kind="Secret" name={selected.name} />
                <DeleteResourceButton
                  clusterId={clusterId!} ns={selected.namespace} kind="Secret" name={selected.name}
                  onDeleted={() => { setSelected(null); queryClient.invalidateQueries({ queryKey: ['secrets', clusterId, ns] }) }}
                />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Type" value={<span className="font-mono text-xs">{selected.type}</span>} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />

            <DrawerSection title={`Data (${selected.keys.length})`} />
            {selected.keys.map((key) => (
              <div key={key} className="flex items-center justify-between gap-3 py-1.5 border-b border-gray-50 last:border-0">
                <span className="font-mono text-xs font-medium text-gray-700 truncate">{key}</span>
                <span className="font-mono text-xs text-gray-400 break-all text-right">
                  {reveal.data ? (reveal.data[key] ?? '—') : '••••••••'}
                </span>
              </div>
            ))}

            {isAdmin && selected.keys.length > 0 && !reveal.data && (
              <div className="pt-3">
                <button
                  onClick={() => reveal.mutate()}
                  disabled={reveal.isPending}
                  className="rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-xs font-medium
                             text-amber-800 hover:bg-amber-100 disabled:opacity-50 transition-colors"
                >
                  {reveal.isPending ? 'Revealing…' : 'Reveal values'}
                </button>
                <p className="mt-1.5 text-[11px] text-gray-400">
                  Revealing is recorded in the audit log.
                </p>
              </div>
            )}
            {reveal.isError && (
              <p className="pt-2 text-sm text-red-600">{apiErrorMessage(reveal.error, 'Reveal failed')}</p>
            )}
            {reveal.data && (
              <button
                onClick={() => reveal.reset()}
                className="mt-3 rounded-lg border border-gray-300 px-3 py-1.5 text-xs text-gray-600
                           hover:bg-gray-50 transition-colors"
              >
                Hide values
              </button>
            )}
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
