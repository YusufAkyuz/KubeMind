import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, apiErrorMessage } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { ErrorBanner } from '../components/ErrorBanner'
import { Modal } from '../components/Modal'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { useToast } from '../components/Toast'
import { useAuth } from '../auth/AuthContext'
import { useAppConfig } from '../hooks/useAppConfig'
import { formatAge } from '../utils/format'
import type { Cluster, PendingCluster } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'health', label: 'Health' },
  { key: 'checked', label: 'Last check' },
  { key: 'actions', label: '' },
]

const PENDING_COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'requestedBy', label: 'Requested by' },
  { key: 'requestedAt', label: 'Requested' },
  { key: 'actions', label: '' },
]

function StatusBadge({ status }: { status: Cluster['status'] }) {
  if (!status || status === 'APPROVED') {
    return (
      <span className="inline-flex rounded-full bg-emerald-50 dark:bg-emerald-500/10 px-2 py-0.5 text-[11px] font-medium text-emerald-700 dark:text-emerald-400 ring-1 ring-inset ring-emerald-600/20">
        Approved
      </span>
    )
  }
  if (status === 'PENDING') {
    return (
      <span className="inline-flex rounded-full bg-amber-50 dark:bg-amber-500/10 px-2 py-0.5 text-[11px] font-medium text-amber-700 dark:text-amber-400 ring-1 ring-inset ring-amber-600/20">
        Pending approval
      </span>
    )
  }
  return (
    <span className="inline-flex rounded-full bg-red-50 dark:bg-red-500/10 px-2 py-0.5 text-[11px] font-medium text-red-700 dark:text-red-400 ring-1 ring-inset ring-red-600/20">
      Rejected
    </span>
  )
}

/** ADMIN-only: requests awaiting a decision. Deliberately a separate, minimal
 *  view (name/owner/date only, no kubeconfig or health) — approving is a
 *  one-time "yes, register this" decision, not a grant of ongoing visibility
 *  into someone else's cluster. Once approved, it disappears from here for
 *  good and the admin has no further access to it. */
function PendingRequests() {
  const toast = useToast()
  const queryClient = useQueryClient()

  const { data, isLoading } = useQuery<PendingCluster[]>({
    queryKey: ['clusters', 'pending'],
    queryFn: async () => (await api.get<PendingCluster[]>('/clusters/pending')).data,
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['clusters', 'pending'] })
    queryClient.invalidateQueries({ queryKey: ['clusters'] })
  }

  const approveMutation = useMutation({
    mutationFn: async (id: number) => (await api.post(`/clusters/${id}/approve`)).data,
    onSuccess: () => { toast.success('Cluster request approved'); invalidate() },
    onError: (e) => toast.error(apiErrorMessage(e, 'Could not approve request')),
  })

  const rejectMutation = useMutation({
    mutationFn: async (id: number) => (await api.post(`/clusters/${id}/reject`)).data,
    onSuccess: () => { toast.success('Cluster request rejected'); invalidate() },
    onError: (e) => toast.error(apiErrorMessage(e, 'Could not reject request')),
  })

  if (isLoading || !data || data.length === 0) return null

  return (
    <div className="mb-6">
      <h2 className="text-sm font-semibold text-gray-700 dark:text-neutral-300 mb-2">
        Pending cluster requests ({data.length})
      </h2>
      <Table columns={PENDING_COLUMNS} minWidth="560px">
        {data.map((p) => (
          <Tr key={p.id}>
            <Td className="font-medium text-gray-900 dark:text-neutral-100">{p.name}</Td>
            <Td className="text-neutral-500 dark:text-neutral-400">{p.createdBy}</Td>
            <Td className="text-gray-400 dark:text-neutral-500 tabular-nums">{formatAge(p.createdAt)} ago</Td>
            <Td>
              <div className="flex justify-end gap-2">
                <button
                  onClick={() => approveMutation.mutate(p.id)}
                  disabled={approveMutation.isPending}
                  className="rounded-md bg-emerald-600 px-2.5 py-1 text-xs font-medium text-white
                             hover:bg-emerald-700 disabled:opacity-50 transition-colors"
                >
                  Approve
                </button>
                <button
                  onClick={() => rejectMutation.mutate(p.id)}
                  disabled={rejectMutation.isPending}
                  className="rounded-md border border-red-200 dark:border-red-500/30 px-2.5 py-1 text-xs text-red-600 dark:text-red-400
                             hover:bg-red-50 transition-colors"
                >
                  Reject
                </button>
              </div>
            </Td>
          </Tr>
        ))}
      </Table>
    </div>
  )
}

export function ClustersPage() {
  const toast = useToast()
  const queryClient = useQueryClient()
  const { isAdmin } = useAuth()
  const impersonationEnabled = useAppConfig().data?.impersonationEnabled ?? false
  const [addOpen, setAddOpen] = useState(false)
  const [deleting, setDeleting] = useState<Cluster | null>(null)
  const [name, setName] = useState('')
  const [kubeconfig, setKubeconfig] = useState('')
  const [addError, setAddError] = useState<string | null>(null)

  const { data, isLoading, isError, error } = useQuery<Cluster[]>({
    queryKey: ['clusters'],
    queryFn: async () => (await api.get<Cluster[]>('/clusters')).data,
  })

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['clusters'] })

  const addMutation = useMutation({
    mutationFn: async () =>
      (await api.post<Cluster>('/clusters', { name: name.trim(), kubeconfig })).data,
    onSuccess: (c) => {
      toast.success(
        c.status === 'PENDING'
          ? `Cluster ${c.name} submitted — waiting for admin approval`
          : `Cluster ${c.name} added`
      )
      setAddOpen(false)
      setName('')
      setKubeconfig('')
      setAddError(null)
      invalidate()
    },
    onError: (e) => setAddError(apiErrorMessage(e, 'Could not add cluster')),
  })

  const testMutation = useMutation({
    mutationFn: async (id: number) => (await api.post<Cluster>(`/clusters/${id}/test`)).data,
    onSuccess: (c) => {
      toast.success(`Cluster ${c.name} is reachable`)
      invalidate()
    },
    onError: (e) => {
      toast.error(apiErrorMessage(e, 'Connection test failed'))
      invalidate()
    },
  })

  const deleteCluster = async () => {
    if (!deleting) return
    try {
      await api.delete(`/clusters/${deleting.id}`)
      toast.success(`Cluster ${deleting.name} removed`)
      setDeleting(null)
      invalidate()
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Delete failed'))
    }
  }

  return (
    <Layout>
      <PageHeader
        title="Clusters"
        subtitle={
          impersonationEnabled && !isAdmin
            ? "Clusters you've registered with your own kubeconfig, plus the built-in one. On the built-in cluster you act as yourself, so what you can see and change there is whatever your Kubernetes permissions allow — an empty page means no access has been granted to you yet, not a broken connection."
            : isAdmin
              ? "Clusters you've registered, plus the built-in one. Other users' clusters are private to them — you only ever see their PENDING requests, below, to approve or reject."
              : "Clusters you've registered with your own kubeconfig. A new cluster stays pending until an admin approves it."
        }
        count={data?.length}
        noun="cluster"
        actions={
          <button
            onClick={() => setAddOpen(true)}
            className="rounded-md bg-blue-600 px-3.5 py-2 text-sm font-medium text-white
                       hover:bg-blue-700 transition-colors"
          >
            Add cluster
          </button>
        }
      />

      {isAdmin && <PendingRequests />}

      {isLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load clusters: ${(error as Error).message}`} />}

      {data && data.length === 0 && (
        <div className="rounded-md border border-dashed border-gray-300 dark:border-neutral-600 px-4 py-8 text-center">
          <p className="text-sm text-neutral-500 dark:text-neutral-400">
            No clusters yet. Add one with your own kubeconfig to get started.
          </p>
        </div>
      )}

      {data && data.length > 0 && (
        <Table columns={COLUMNS} minWidth="600px">
          {data.map((c) => (
            <Tr key={c.id}>
              <Td className="font-medium text-gray-900 dark:text-neutral-100">
                {c.name}
                {c.builtIn && (
                  <span className="ml-2 rounded-full bg-gray-100 dark:bg-neutral-700 px-2 py-0.5 text-[10px] font-medium text-neutral-500 dark:text-neutral-400 ring-1 ring-inset ring-gray-500 dark:ring-neutral-500/20">
                    built-in
                  </span>
                )}
              </Td>
              <Td>
                {c.builtIn ? (
                  <span className="text-xs text-gray-400 dark:text-neutral-500">—</span>
                ) : (
                  <StatusBadge status={c.status} />
                )}
              </Td>
              <Td>
                {c.builtIn ? (
                  <span className="text-xs text-gray-400 dark:text-neutral-500">always available</span>
                ) : c.status !== 'APPROVED' ? (
                  <span className="text-xs text-gray-400 dark:text-neutral-500">—</span>
                ) : (
                  <span className={`inline-flex items-center gap-1.5 text-xs font-medium ${
                    c.lastCheckOk === true ? 'text-emerald-600 dark:text-emerald-400'
                    : c.lastCheckOk === false ? 'text-red-600 dark:text-red-400' : 'text-gray-400 dark:text-neutral-500'
                  }`}>
                    <span className={`w-1.5 h-1.5 rounded-full ${
                      c.lastCheckOk === true ? 'bg-emerald-500'
                      : c.lastCheckOk === false ? 'bg-red-500' : 'bg-gray-300 dark:bg-neutral-600'
                    }`} />
                    {c.lastCheckOk === true ? 'Reachable' : c.lastCheckOk === false ? 'Unreachable' : 'Unknown'}
                  </span>
                )}
              </Td>
              <Td className="text-gray-400 dark:text-neutral-500 tabular-nums">
                {c.lastCheckedAt ? `${formatAge(c.lastCheckedAt)} ago` : '—'}
              </Td>
              <Td>
                {!c.builtIn && (
                  <div className="flex justify-end gap-2">
                    {c.status === 'APPROVED' && (
                      <button
                        onClick={() => testMutation.mutate(c.id)}
                        disabled={testMutation.isPending}
                        className="rounded-md border border-gray-300 dark:border-neutral-600 px-2.5 py-1 text-xs text-neutral-600 dark:text-neutral-400
                                   hover:bg-gray-50 dark:hover:bg-neutral-800 disabled:opacity-50 transition-colors"
                      >
                        Test
                      </button>
                    )}
                    <button
                      onClick={() => setDeleting(c)}
                      className="rounded-md border border-red-200 dark:border-red-500/30 px-2.5 py-1 text-xs text-red-600 dark:text-red-400
                                 hover:bg-red-50 transition-colors"
                    >
                      Remove
                    </button>
                  </div>
                )}
              </Td>
            </Tr>
          ))}
        </Table>
      )}

      {/* ── Add cluster ──────────────────────────────────────────────────── */}
      <Modal open={addOpen} title="Add cluster" onClose={() => setAddOpen(false)} wide>
        <div className="space-y-4">
          <div>
            <label className="block text-xs text-neutral-500 dark:text-neutral-400 mb-1.5">Cluster name</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="staging"
              maxLength={128}
              className="w-full sm:w-72 rounded-md border border-gray-300 dark:border-neutral-600 bg-white dark:bg-neutral-800
                         text-gray-900 dark:text-neutral-100 px-3 py-2 text-sm
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
          </div>
          <div>
            <label className="block text-xs text-neutral-500 dark:text-neutral-400 mb-1.5">Kubeconfig</label>
            <textarea
              value={kubeconfig}
              onChange={(e) => setKubeconfig(e.target.value)}
              spellCheck={false}
              placeholder="Paste the kubeconfig YAML here…"
              className="w-full h-64 rounded-md border border-neutral-800 bg-neutral-950 text-neutral-100
                         font-mono text-xs leading-5 p-3 resize-none
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
            <p className="mt-1.5 text-xs text-gray-400 dark:text-neutral-500">
              Prefer a kubeconfig bound to a least-privilege (read-only) ServiceAccount.
              The connection is tested before the cluster is saved.
              {!isAdmin && ' An admin needs to approve it before you can use it.'}
            </p>
          </div>
          {addError && <p className="text-sm text-red-600 dark:text-red-400">{addError}</p>}
          <div className="flex justify-end gap-2">
            <button
              onClick={() => setAddOpen(false)}
              disabled={addMutation.isPending}
              className="rounded-md border border-gray-300 dark:border-neutral-600 px-3.5 py-2 text-sm text-gray-700 dark:text-neutral-300
                         hover:bg-gray-50 dark:hover:bg-neutral-800 disabled:opacity-50 transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={() => addMutation.mutate()}
              disabled={addMutation.isPending || !name.trim() || !kubeconfig.trim()}
              className="rounded-md bg-blue-600 px-3.5 py-2 text-sm font-medium text-white
                         hover:bg-blue-700 disabled:opacity-50 transition-colors"
            >
              {addMutation.isPending ? 'Testing connection…' : 'Add cluster'}
            </button>
          </div>
        </div>
      </Modal>

      {/* ── Remove confirm ───────────────────────────────────────────────── */}
      <ConfirmDialog
        open={!!deleting}
        title={`Remove cluster ${deleting?.name ?? ''}`}
        message={
          <>
            The stored kubeconfig will be deleted. Saved AI diagnoses and audit entries
            for this cluster are kept.
          </>
        }
        confirmLabel="Remove"
        danger
        requireText={deleting?.name}
        onConfirm={deleteCluster}
        onClose={() => setDeleting(null)}
      />
    </Layout>
  )
}
