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
import { formatAge } from '../utils/format'
import type { Cluster } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'createdBy', label: 'Added by' },
  { key: 'checked', label: 'Last check' },
  { key: 'actions', label: '' },
]

export function ClustersPage() {
  const toast = useToast()
  const queryClient = useQueryClient()
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
      toast.success(`Cluster ${c.name} added`)
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
        subtitle="Connected Kubernetes clusters. Kubeconfigs are encrypted at rest and never leave the server."
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

      {isLoading && <p className="text-sm text-gray-400">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load clusters: ${(error as Error).message}`} />}

      {data && (
        <Table columns={COLUMNS} minWidth="560px">
          {data.map((c) => (
            <Tr key={c.id}>
              <Td className="font-medium text-gray-900">
                {c.name}
                {c.builtIn && (
                  <span className="ml-2 rounded-full bg-gray-100 px-2 py-0.5 text-[10px] font-medium text-gray-500 ring-1 ring-inset ring-gray-500/20">
                    built-in
                  </span>
                )}
              </Td>
              <Td>
                {c.builtIn ? (
                  <span className="text-xs text-gray-400">always available</span>
                ) : (
                  <span className={`inline-flex items-center gap-1.5 text-xs font-medium ${
                    c.lastCheckOk === true ? 'text-emerald-600'
                    : c.lastCheckOk === false ? 'text-red-600' : 'text-gray-400'
                  }`}>
                    <span className={`w-1.5 h-1.5 rounded-full ${
                      c.lastCheckOk === true ? 'bg-emerald-500'
                      : c.lastCheckOk === false ? 'bg-red-500' : 'bg-gray-300'
                    }`} />
                    {c.lastCheckOk === true ? 'Reachable' : c.lastCheckOk === false ? 'Unreachable' : 'Unknown'}
                  </span>
                )}
              </Td>
              <Td className="text-gray-500">{c.createdBy ?? '—'}</Td>
              <Td className="text-gray-400 tabular-nums">
                {c.lastCheckedAt ? `${formatAge(c.lastCheckedAt)} ago` : '—'}
              </Td>
              <Td>
                {!c.builtIn && (
                  <div className="flex justify-end gap-2">
                    <button
                      onClick={() => testMutation.mutate(c.id)}
                      disabled={testMutation.isPending}
                      className="rounded-md border border-gray-300 px-2.5 py-1 text-xs text-gray-600
                                 hover:bg-gray-50 disabled:opacity-50 transition-colors"
                    >
                      Test
                    </button>
                    <button
                      onClick={() => setDeleting(c)}
                      className="rounded-md border border-red-200 px-2.5 py-1 text-xs text-red-600
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
            <label className="block text-xs text-gray-500 mb-1.5">Cluster name</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="staging"
              maxLength={128}
              className="w-full sm:w-72 rounded-md border border-gray-300 px-3 py-2 text-sm
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1.5">Kubeconfig</label>
            <textarea
              value={kubeconfig}
              onChange={(e) => setKubeconfig(e.target.value)}
              spellCheck={false}
              placeholder="Paste the kubeconfig YAML here…"
              className="w-full h-64 rounded-md border border-gray-300 bg-gray-950 text-gray-100
                         font-mono text-xs leading-5 p-3 resize-none
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
            <p className="mt-1.5 text-xs text-gray-400">
              Prefer a kubeconfig bound to a least-privilege (read-only) ServiceAccount.
              The connection is tested before the cluster is saved.
            </p>
          </div>
          {addError && <p className="text-sm text-red-600">{addError}</p>}
          <div className="flex justify-end gap-2">
            <button
              onClick={() => setAddOpen(false)}
              disabled={addMutation.isPending}
              className="rounded-md border border-gray-300 px-3.5 py-2 text-sm text-gray-700
                         hover:bg-gray-50 disabled:opacity-50 transition-colors"
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
