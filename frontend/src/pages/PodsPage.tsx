import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, apiErrorMessage } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { useToast } from '../components/Toast'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { StatusBadge } from '../components/StatusBadge'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner } from '../components/ErrorBanner'
import { useSSE } from '../hooks/useSSE'
import { formatAge } from '../utils/format'
import { IconTerminal } from '../components/Icons'
import { ExplainPanel } from '../components/ExplainPanel'
import { CreateResourceButton } from '../components/CreateResourceButton'
import type { Pod, PodMetrics } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'ready', label: 'Ready' },
  { key: 'restarts', label: 'Restarts' },
  { key: 'cpu', label: 'CPU', className: 'hidden lg:table-cell' },
  { key: 'memory', label: 'Memory', className: 'hidden lg:table-cell' },
  { key: 'node', label: 'Node', className: 'hidden lg:table-cell' },
  { key: 'ip', label: 'IP', className: 'hidden xl:table-cell' },
  { key: 'age', label: 'Age' },
]

export function PodsPage() {
  const { clusterId, ns } = useParams<{ clusterId: string; ns: string }>()
  const navigate = useNavigate()
  const { isAdmin } = useAuth()
  const toast = useToast()
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<Pod | null>(null)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const queryKey = ['pods', clusterId, ns]

  const deletePod = async () => {
    if (!selected) return
    try {
      await api.delete(`/clusters/${clusterId}/namespaces/${ns}/pods/${selected.name}`)
      toast.success(`Pod ${selected.name} deleted`)
      setSelected(null)
      queryClient.invalidateQueries({ queryKey })
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Delete failed'))
    }
  }

  const noNamespace = ns === '_'
  const { data, isLoading, isError, error } = useQuery<Pod[]>({
    queryKey,
    queryFn: async () => (await api.get<Pod[]>(`/clusters/${clusterId}/namespaces/${ns}/pods`)).data,
    enabled: !!clusterId && !!ns && !noNamespace,
  })

  useSSE<Pod[]>(clusterId && ns ? `/api/clusters/${clusterId}/watch/namespaces/${ns}/pods` : null, queryKey)

  // metrics-server is optional — an empty array (not an error) means it isn't installed.
  const { data: metrics } = useQuery<PodMetrics[]>({
    queryKey: ['pod-metrics', clusterId, ns],
    queryFn: async () => (await api.get<PodMetrics[]>(`/clusters/${clusterId}/namespaces/${ns}/metrics/pods`)).data,
    enabled: !!clusterId && !!ns && !noNamespace,
    refetchInterval: 15_000,
    retry: false,
  })
  const metricsByName = new Map((metrics ?? []).map((m) => [m.name, m]))

  return (
    <Layout>
      <PageHeader
        title="Pods"
        subtitle={ns ? `namespace: ${ns}` : undefined}
        count={data?.length}
        noun="pod"
        actions={<CreateResourceButton clusterId={clusterId} ns={ns} kind="Pod" />}
      />

      {noNamespace && (
        <div className="rounded-xl border border-dashed border-gray-200 bg-white px-6 py-12 text-center">
          <p className="text-sm text-gray-400">Select a namespace from the sidebar to view pods.</p>
        </div>
      )}
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
                <Td className="hidden lg:table-cell text-gray-500">{metricsByName.get(pod.name)?.cpuUsage ?? '—'}</Td>
                <Td className="hidden lg:table-cell text-gray-500">{metricsByName.get(pod.name)?.memoryUsage ?? '—'}</Td>
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
        {selected && ns && (
          <>
            <div className="pb-3">
              <ExplainPanel
                key={`${clusterId}/${ns}/${selected.name}`}
                clusterId={clusterId!}
                namespace={ns}
                kind="Pod"
                name={selected.name}
              />
            </div>

            <DrawerSection title="Overview" />
            <DrawerRow label="Status" value={<StatusBadge status={selected.phase} />} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Node" value={selected.nodeName} />
            <DrawerRow label="Pod IP" value={<span className="font-mono text-xs">{selected.podIP}</span>} />
            <DrawerRow label="Restarts" value={selected.restartCount} />
            <DrawerRow label="CPU (usage)" value={metricsByName.get(selected.name)?.cpuUsage ?? '—'} />
            <DrawerRow label="Memory (usage)" value={metricsByName.get(selected.name)?.memoryUsage ?? '—'} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />

            {isAdmin && (
              <>
                <DrawerSection title="Actions" />
                <button
                  onClick={() => setDeleteOpen(true)}
                  className="rounded-md border border-red-200 px-3 py-1.5 text-xs font-medium
                             text-red-600 hover:bg-red-50 transition-colors"
                >
                  Delete pod
                </button>
              </>
            )}

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
                            navigate(`/clusters/${clusterId}/namespaces/${ns}/pods/${selected.name}/logs?container=${c.name}`)
                          }}
                          className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-800 transition-colors"
                          title="View logs"
                        >
                          <IconTerminal className="w-3.5 h-3.5" />
                          Logs
                        </button>
                        {isAdmin && (
                          <button
                            onClick={(e) => {
                              e.stopPropagation()
                              navigate(`/clusters/${clusterId}/namespaces/${ns}/pods/${selected.name}/exec?container=${c.name}`)
                            }}
                            className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-800 transition-colors"
                            title="Open terminal"
                          >
                            <IconTerminal className="w-3.5 h-3.5" />
                            Exec
                          </button>
                        )}
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

      <ConfirmDialog
        open={deleteOpen}
        title={`Delete pod ${selected?.name ?? ''}`}
        message={
          <>
            The pod will be terminated. If it is managed by a Deployment/ReplicaSet a replacement
            will be created automatically; a standalone pod is <span className="font-medium">gone for good</span>.
          </>
        }
        confirmLabel="Delete"
        danger
        requireText={selected?.name}
        onConfirm={deletePod}
        onClose={() => setDeleteOpen(false)}
      />
    </Layout>
  )
}
