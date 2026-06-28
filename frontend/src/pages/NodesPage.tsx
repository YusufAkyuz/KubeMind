import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { Layout } from '../components/Layout'
import { StatusBadge } from '../components/StatusBadge'
import { DetailDrawer, DrawerRow } from '../components/DetailDrawer'
import { useSSE } from '../hooks/useSSE'
import { formatAge } from '../utils/format'
import type { NodeResource } from '../types/k8s'

export function NodesPage() {
  const [selected, setSelected] = useState<NodeResource | null>(null)

  const queryKey = ['nodes']
  const { data, isLoading, isError, error } = useQuery<NodeResource[]>({
    queryKey,
    queryFn: async () => (await api.get<NodeResource[]>('/k8s/nodes')).data,
  })

  useSSE<NodeResource[]>('/api/k8s/watch/nodes', queryKey)

  return (
    <Layout>
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-xl font-semibold text-gray-800">Nodes</h1>
        {data && (
          <span className="text-sm text-gray-500">{data.length} node{data.length !== 1 ? 's' : ''}</span>
        )}
      </div>

      {isLoading && <p className="text-gray-500 text-sm">Loading nodes…</p>}
      {isError && (
        <div className="text-sm text-red-600 bg-red-50 rounded-md px-4 py-3">
          Could not load nodes: {(error as Error).message}
        </div>
      )}

      {data && (
        <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white shadow-sm">
          <table className="w-full text-sm min-w-[640px]">
            <thead>
              <tr className="bg-gray-50 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide">
                <th className="px-4 py-3">Name</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3">Roles</th>
                <th className="px-4 py-3">Version</th>
                <th className="px-4 py-3">CPU</th>
                <th className="px-4 py-3">Memory</th>
                <th className="px-4 py-3">Age</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {data.map((node) => (
                <tr
                  key={node.name}
                  onClick={() => setSelected(node)}
                  className="hover:bg-blue-50 cursor-pointer transition-colors"
                >
                  <td className="px-4 py-3 font-medium text-gray-800">{node.name}</td>
                  <td className="px-4 py-3"><StatusBadge status={node.status} /></td>
                  <td className="px-4 py-3 text-gray-600">{node.roles}</td>
                  <td className="px-4 py-3 text-gray-500 font-mono text-xs">{node.kubeletVersion ?? '—'}</td>
                  <td className="px-4 py-3 text-gray-600">{node.cpuCapacity ?? '—'}</td>
                  <td className="px-4 py-3 text-gray-600">{node.memoryCapacity ?? '—'}</td>
                  <td className="px-4 py-3 text-gray-500">{formatAge(node.creationTimestamp)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <DetailDrawer
        open={selected !== null}
        title={selected?.name ?? ''}
        onClose={() => setSelected(null)}
      >
        {selected && (
          <>
            <DrawerRow label="Status" value={<StatusBadge status={selected.status} />} />
            <DrawerRow label="Roles" value={selected.roles} />
            <DrawerRow label="Kubelet version" value={selected.kubeletVersion} />
            <DrawerRow label="OS image" value={selected.osImage} />
            <DrawerRow label="CPU capacity" value={selected.cpuCapacity} />
            <DrawerRow label="Memory capacity" value={selected.memoryCapacity} />
            <DrawerRow label="CPU allocatable" value={selected.cpuAllocatable} />
            <DrawerRow label="Memory allocatable" value={selected.memoryAllocatable} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
