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
import { formatAge } from '../utils/format'
import type { Pv } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'capacity', label: 'Capacity' },
  { key: 'claim', label: 'Claim', className: 'hidden md:table-cell' },
  { key: 'age', label: 'Age' },
]

// PV phase → StatusBadge vocabulary.
const PHASE: Record<string, string> = {
  Bound: 'Ready', Available: 'Ready', Released: 'Pending', Failed: 'Failed',
}

// PVs are cluster-scoped, so this page doesn't use the namespaced-list hook.
export function PvsPage() {
  const { clusterId } = useParams<{ clusterId: string }>()
  const [selected, setSelected] = useState<Pv | null>(null)

  const { data, isLoading, isError, error } = useQuery<Pv[]>({
    queryKey: ['persistentvolumes', clusterId],
    queryFn: async () => (await api.get<Pv[]>(`/clusters/${clusterId}/persistentvolumes`)).data,
    enabled: !!clusterId,
    refetchInterval: 20_000,
  })

  return (
    <Layout>
      <PageHeader title="Persistent Volumes" subtitle="cluster-scoped"
                  count={data?.length} noun="volume" />

      {isLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load volumes: ${(error as Error).message}`} />}

      {data && (
        <Table columns={COLUMNS}>
          {data.map((pv) => (
            <Tr key={pv.name} onClick={() => setSelected(pv)} highlighted={selected?.name === pv.name}>
              <Td className="font-medium text-gray-900 dark:text-neutral-100">{pv.name}</Td>
              <Td><StatusBadge status={PHASE[pv.status] ?? 'Unknown'} /></Td>
              <Td className="text-gray-600 dark:text-neutral-400 tabular-nums">{pv.capacity ?? '—'}</Td>
              <Td className="hidden md:table-cell font-mono text-xs text-gray-400 dark:text-neutral-500">{pv.claimRef ?? '—'}</Td>
              <Td className="text-gray-400 dark:text-neutral-500 tabular-nums">{formatAge(pv.creationTimestamp)}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle="PersistentVolume"
                    onClose={() => setSelected(null)}>
        {selected && (
          <>
            <DrawerSection title="Overview" />
            <DrawerRow label="Status" value={<StatusBadge status={PHASE[selected.status] ?? 'Unknown'} />} />
            <DrawerRow label="Capacity" value={selected.capacity} />
            <DrawerRow label="Storage class" value={selected.storageClass} />
            <DrawerRow label="Reclaim policy" value={selected.reclaimPolicy} />
            <DrawerRow label="Access modes" value={selected.accessModes.join(', ') || '—'} />
            <DrawerRow label="Bound claim" value={<span className="font-mono text-xs break-all">{selected.claimRef ?? '—'}</span>} />
            <DrawerRow label="Age" value={formatAge(selected.creationTimestamp)} />
          </>
        )}
      </DetailDrawer>
    </Layout>
  )
}
