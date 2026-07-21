import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import { api, apiErrorMessage } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { StatusBadge } from '../components/StatusBadge'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner } from '../components/ErrorBanner'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { EditYamlButton } from '../components/EditYamlButton'
import { useCanWrite } from '../auth/useCanWrite'
import { formatAge } from '../utils/format'
import type { Namespace } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'age', label: 'Age' },
]

export function NamespacesPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { clusterId } = useParams<{ clusterId: string }>()
  const canWrite = useCanWrite(clusterId)
  const [selected, setSelected] = useState<Namespace | null>(null)
  const [deleteOpen, setDeleteOpen] = useState(false)

  const { data, isLoading, isError, error } = useQuery<Namespace[]>({
    queryKey: ['namespaces', clusterId],
    queryFn: async () => (await api.get<Namespace[]>(`/clusters/${clusterId}/namespaces`)).data,
    enabled: !!clusterId,
  })

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['namespaces', clusterId] })
    setSelected(null)
  }

  const deleteNamespace = async () => {
    if (!selected) return
    try {
      await api.delete(`/clusters/${clusterId}/namespaces/${selected.name}`)
      refresh()
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Delete failed'))
    }
  }

  return (
    <Layout>
      <PageHeader title="Namespaces" count={data?.length} noun="namespace" />

      {isLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load namespaces: ${(error as Error).message}`} />}

      {data && (
        <Table columns={COLUMNS} minWidth="360px">
          {data.map((ns) => (
            <Tr key={ns.name} onClick={() => setSelected(ns)} highlighted={selected?.name === ns.name}>
              <Td className="font-medium text-blue-600 dark:text-blue-400">{ns.name}</Td>
              <Td><StatusBadge status={ns.phase ?? 'Unknown'} /></Td>
              <Td className="text-gray-400 dark:text-neutral-500 tabular-nums">{formatAge(ns.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle="Namespace"
                    onClose={() => setSelected(null)}>
        {selected && (
          <>
            <div className="flex flex-wrap gap-2 pb-3">
              <button
                onClick={() => navigate(`/clusters/${clusterId}/namespaces/${selected.name}/pods`)}
                className="rounded-md bg-blue-600 px-3 py-1.5 text-xs font-medium text-white
                           hover:bg-blue-700 transition-colors"
              >
                View pods
              </button>
              {canWrite && (
                <EditYamlButton clusterId={clusterId!} kind="Namespace" name={selected.name} onApplied={refresh} />
              )}
              {canWrite && (
                <button
                  onClick={() => setDeleteOpen(true)}
                  className="rounded-md border border-red-200 dark:border-red-500/30 px-3 py-1.5 text-xs font-medium
                             text-red-600 dark:text-red-400 hover:bg-red-50 transition-colors"
                >
                  Delete
                </button>
              )}
            </div>

            <DrawerSection title="Overview" />
            <DrawerRow label="Status" value={<StatusBadge status={selected.phase ?? 'Unknown'} />} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />
          </>
        )}
      </DetailDrawer>

      <ConfirmDialog
        open={deleteOpen}
        title={`Delete namespace ${selected?.name ?? ''}`}
        message={
          <>
            This deletes the namespace and <span className="font-semibold text-red-600 dark:text-red-400">every resource inside it</span> —
            pods, deployments, secrets, everything. This cannot be undone.
          </>
        }
        confirmLabel="Delete"
        danger
        requireText={selected?.name}
        onConfirm={deleteNamespace}
        onClose={() => setDeleteOpen(false)}
      />
    </Layout>
  )
}
