import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { StatusBadge } from '../components/StatusBadge'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner } from '../components/ErrorBanner'
import { ClusterInsightsPanel } from '../components/ClusterInsightsPanel'
import { useSSE } from '../hooks/useSSE'
import { useTerminalPanel } from '../terminal/TerminalPanelContext'
import { formatAge } from '../utils/format'
import type { NodeMetrics, NodeResource } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'roles', label: 'Roles' },
  { key: 'version', label: 'Version', className: 'hidden md:table-cell' },
  { key: 'cpu', label: 'CPU', className: 'hidden lg:table-cell' },
  { key: 'memory', label: 'Memory', className: 'hidden lg:table-cell' },
  { key: 'age', label: 'Age' },
]

export function NodesPage() {
  const { clusterId } = useParams<{ clusterId: string }>()
  const terminalPanel = useTerminalPanel()
  const [selected, setSelected] = useState<NodeResource | null>(null)
  const queryKey = ['nodes', clusterId]

  const { data, isLoading, isError, error } = useQuery<NodeResource[]>({
    queryKey,
    queryFn: async () => (await api.get<NodeResource[]>(`/clusters/${clusterId}/nodes`)).data,
    enabled: !!clusterId,
  })

  const streamError = useSSE<NodeResource[]>(clusterId ? `/api/clusters/${clusterId}/watch/nodes` : null, queryKey)

  // metrics-server is optional — an empty array (not an error) means it isn't installed.
  const { data: metrics } = useQuery<NodeMetrics[]>({
    queryKey: ['node-metrics', clusterId],
    queryFn: async () => (await api.get<NodeMetrics[]>(`/clusters/${clusterId}/metrics/nodes`)).data,
    enabled: !!clusterId,
    refetchInterval: 15_000,
    retry: false,
  })
  const metricsByName = new Map((metrics ?? []).map((m) => [m.name, m]))
  const metricsAvailable = (metrics?.length ?? 0) > 0

  return (
    <Layout>
      <PageHeader title="Nodes" count={data?.length} noun="node" />

      {clusterId && <ClusterInsightsPanel clusterId={clusterId} />}

      {isLoading && !isError && !streamError && <p className="text-sm text-gray-400">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load nodes: ${(error as Error).message}`} />}
      {!isError && streamError && <ErrorBanner message={streamError} />}

      {data && (
        <Table columns={COLUMNS}>
          {data.map((node) => (
            <Tr
              key={node.name}
              onClick={() => setSelected(node)}
              highlighted={selected?.name === node.name}
            >
              <Td className="font-medium text-gray-900">{node.name}</Td>
              <Td><StatusBadge status={node.status} /></Td>
              <Td className="text-gray-500">{node.roles}</Td>
              <Td className="hidden md:table-cell font-mono text-xs text-gray-400">{node.kubeletVersion ?? '—'}</Td>
              <Td className="hidden lg:table-cell text-gray-500">
                {metricsByName.get(node.name)?.cpuUsage
                  ? `${metricsByName.get(node.name)!.cpuUsage} / ${node.cpuCapacity ?? '—'}`
                  : node.cpuCapacity ?? '—'}
              </Td>
              <Td className="hidden lg:table-cell text-gray-500">
                {metricsByName.get(node.name)?.memoryUsage
                  ? `${metricsByName.get(node.name)!.memoryUsage} / ${node.memoryCapacity ?? '—'}`
                  : node.memoryCapacity ?? '—'}
              </Td>
              <Td className="text-gray-400 tabular-nums">{formatAge(node.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer
        open={!!selected}
        title={selected?.name ?? ''}
        subtitle="Node"
        onClose={() => setSelected(null)}
      >
        {selected && (
          <>
            {/* Not ADMIN-gated: node shell schedules a debug pod using the
                target cluster's own kubeconfig — real Kubernetes RBAC decides
                what it can actually do, same as any other cluster action. */}
            <div className="pb-3">
              <button
                onClick={() => terminalPanel.openNodeExec(clusterId!, selected.name)}
                className="rounded-md border border-amber-300 bg-amber-50 px-3 py-1.5 text-xs font-medium
                           text-amber-800 hover:bg-amber-100 transition-colors"
              >
                Node shell
              </button>
            </div>

            <DrawerSection title="Overview" />
            <DrawerRow label="Status" value={<StatusBadge status={selected.status} />} />
            <DrawerRow label="Roles" value={selected.roles} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />

            <DrawerSection title="System" />
            <DrawerRow label="Kubelet" value={selected.kubeletVersion} />
            <DrawerRow label="OS" value={selected.osImage} />

            <DrawerSection title="Resources" />
            <DrawerRow label="CPU (capacity)" value={selected.cpuCapacity} />
            <DrawerRow label="CPU (allocatable)" value={selected.cpuAllocatable} />
            <DrawerRow label="Memory (capacity)" value={selected.memoryCapacity} />
            <DrawerRow label="Memory (allocatable)" value={selected.memoryAllocatable} />
            <DrawerRow label="CPU (usage)"
                       value={metricsByName.get(selected.name)?.cpuUsage
                         ?? (metricsAvailable ? '—' : 'metrics-server not installed')} />
            <DrawerRow label="Memory (usage)"
                       value={metricsByName.get(selected.name)?.memoryUsage
                         ?? (metricsAvailable ? '—' : 'metrics-server not installed')} />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
