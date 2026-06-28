import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { StatusBadge } from '../components/StatusBadge'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner } from '../components/ErrorBanner'
import { useSSE } from '../hooks/useSSE'
import { formatAge } from '../utils/format'
import { IconTerminal } from '../components/Icons'
import type { Pod } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'ready', label: 'Ready' },
  { key: 'restarts', label: 'Restarts' },
  { key: 'node', label: 'Node', className: 'hidden lg:table-cell' },
  { key: 'ip', label: 'IP', className: 'hidden xl:table-cell' },
  { key: 'age', label: 'Age' },
]

export function PodsPage() {
  const { ns } = useParams<{ ns: string }>()
  const navigate = useNavigate()
  const [selected, setSelected] = useState<Pod | null>(null)
  const queryKey = ['pods', ns]

  const { data, isLoading, isError, error } = useQuery<Pod[]>({
    queryKey,
    queryFn: async () => (await api.get<Pod[]>(`/k8s/namespaces/${ns}/pods`)).data,
    enabled: !!ns,
  })

  useSSE<Pod[]>(ns ? `/api/k8s/watch/namespaces/${ns}/pods` : null, queryKey)

  return (
    <Layout>
      <PageHeader
        title="Pods"
        subtitle={ns ? `namespace: ${ns}` : undefined}
        count={data?.length}
        noun="pod"
      />

      {isLoading && <p className="text-sm text-gray-400">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load pods: ${(error as Error).message}`} />}

      {data && (
        <Table columns={COLUMNS}>
          {data.map((pod) => {
            const readyCount = pod.containers.filter((c) => c.ready).length
            const highRestarts = pod.restartCount > 5
            return (
              <Tr
                key={`${pod.namespace}/${pod.name}`}
                onClick={() => setSelected(pod)}
                highlighted={selected?.name === pod.name}
              >
                <Td className="font-medium text-gray-900">{pod.name}</Td>
                <Td><StatusBadge status={pod.phase} /></Td>
                <Td className="tabular-nums text-gray-500">{readyCount}/{pod.containers.length}</Td>
                <Td>
                  <span className={highRestarts ? 'font-semibold text-red-600' : 'text-gray-500 tabular-nums'}>
                    {pod.restartCount}
                  </span>
                </Td>
                <Td className="hidden lg:table-cell text-gray-400 text-xs">{pod.nodeName ?? '—'}</Td>
                <Td className="hidden xl:table-cell font-mono text-xs text-gray-400">{pod.podIP ?? '—'}</Td>
                <Td className="text-gray-400 tabular-nums">{formatAge(pod.creationTimestamp)}</Td>
              </Tr>
            )
          })}
        </Table>
      )}

      <DetailDrawer
        open={!!selected}
        title={selected?.name ?? ''}
        subtitle={`Pod · ${ns}`}
        onClose={() => setSelected(null)}
      >
        {selected && (
          <>
            <DrawerSection title="Overview" />
            <DrawerRow label="Status" value={<StatusBadge status={selected.phase} />} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Node" value={selected.nodeName} />
            <DrawerRow label="Pod IP" value={<span className="font-mono text-xs">{selected.podIP}</span>} />
            <DrawerRow label="Restarts" value={selected.restartCount} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />

            {selected.containers.length > 0 && (
              <>
                <DrawerSection title="Containers" />
                {selected.containers.map((c) => (
                  <div key={c.name} className="rounded-lg border border-gray-200 p-3 space-y-1.5">
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm font-medium text-gray-900 truncate">{c.name}</span>
                      <div className="flex items-center gap-2 shrink-0">
                        <StatusBadge status={c.ready ? 'Ready' : 'NotReady'} />
                        <button
                          onClick={(e) => {
                            e.stopPropagation()
                            navigate(`/namespaces/${ns}/pods/${selected.name}/logs?container=${c.name}`)
                          }}
                          className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-800 transition-colors"
                          title="View logs"
                        >
                          <IconTerminal className="w-3.5 h-3.5" />
                          Logs
                        </button>
                      </div>
                    </div>
                    <p className="font-mono text-xs text-gray-400 break-all">{c.image}</p>
                    <p className="text-xs text-gray-400">Restarts: {c.restartCount}</p>
                  </div>
                ))}
              </>
            )}
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
