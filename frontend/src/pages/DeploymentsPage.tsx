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
import { useSSE } from '../hooks/useSSE'
import { formatAge } from '../utils/format'
import { useAuth } from '../auth/AuthContext'
import { DeploymentActions } from '../components/DeploymentActions'
import type { Deployment } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'ready', label: 'Ready' },
  { key: 'strategy', label: 'Strategy', className: 'hidden md:table-cell' },
  { key: 'image', label: 'Image', className: 'hidden lg:table-cell' },
  { key: 'age', label: 'Age' },
]

function replicaStatus(d: Deployment): string {
  if (d.desiredReplicas === 0) return 'Unknown'
  if (d.readyReplicas === d.desiredReplicas) return 'Ready'
  if (d.readyReplicas === 0) return 'Failed'
  return 'Pending'
}

export function DeploymentsPage() {
  const { clusterId, ns } = useParams<{ clusterId: string; ns: string }>()
  const { isAdmin } = useAuth()
  const [selected, setSelected] = useState<Deployment | null>(null)
  const queryKey = ['deployments', clusterId, ns]

  const noNamespace = ns === '_'
  const { data, isLoading, isError, error } = useQuery<Deployment[]>({
    queryKey,
    queryFn: async () => (await api.get<Deployment[]>(`/clusters/${clusterId}/namespaces/${ns}/deployments`)).data,
    enabled: !!clusterId && !!ns && !noNamespace,
  })

  useSSE<Deployment[]>(clusterId && ns ? `/api/clusters/${clusterId}/watch/namespaces/${ns}/deployments` : null, queryKey)

  return (
    <Layout>
      <PageHeader
        title="Deployments"
        subtitle={ns ? `namespace: ${ns}` : undefined}
        count={data?.length}
        noun="deployment"
      />

      {noNamespace && (
        <div className="rounded-xl border border-dashed border-gray-200 bg-white px-6 py-12 text-center">
          <p className="text-sm text-gray-400">Select a namespace from the sidebar to view deployments.</p>
        </div>
      )}
      {isLoading && <p className="text-sm text-gray-400">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load deployments: ${(error as Error).message}`} />}

      {data && (
        <Table columns={COLUMNS}>
          {data.map((d) => (
            <Tr
              key={`${d.namespace}/${d.name}`}
              onClick={() => setSelected(d)}
              highlighted={selected?.name === d.name}
            >
              <Td className="font-medium text-gray-900">{d.name}</Td>
              <Td><StatusBadge status={replicaStatus(d)} /></Td>
              <Td className="tabular-nums text-gray-500">{d.readyReplicas}/{d.desiredReplicas}</Td>
              <Td className="hidden md:table-cell text-xs text-gray-400">{d.strategy}</Td>
              <Td className="hidden lg:table-cell font-mono text-xs text-gray-400 max-w-[240px] truncate">
                {d.image ?? '—'}
              </Td>
              <Td className="text-gray-400 tabular-nums">{formatAge(d.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer
        open={!!selected}
        title={selected?.name ?? ''}
        subtitle={`Deployment · ${ns}`}
        onClose={() => setSelected(null)}
      >
        {selected && ns && (
          <>
            {isAdmin && (
              <div className="pb-3">
                <DeploymentActions
                  key={`${clusterId}/${ns}/${selected.name}`}
                  clusterId={clusterId!}
                  namespace={ns}
                  deployment={selected}
                  onActionDone={() => setSelected(null)}
                />
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Status" value={<StatusBadge status={replicaStatus(selected)} />} />
            <DrawerRow label="Namespace" value={selected.namespace} />
            <DrawerRow label="Strategy" value={selected.strategy} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />

            <DrawerSection title="Replicas" />
            <DrawerRow label="Desired" value={selected.desiredReplicas} />
            <DrawerRow label="Ready" value={selected.readyReplicas} />
            <DrawerRow label="Available" value={selected.availableReplicas} />

            <DrawerSection title="Image" />
            <DrawerRow label="Container 0" value={
              <span className="font-mono text-xs break-all">{selected.image ?? '—'}</span>
            } />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
