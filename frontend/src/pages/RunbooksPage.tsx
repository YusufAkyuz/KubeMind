import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, apiErrorMessage } from '../api/client'
import { useToast } from '../components/Toast'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { ErrorBanner } from '../components/ErrorBanner'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { formatAge } from '../utils/format'

interface Runbook {
  id: string
  title: string
  content: string
  createdBy: string
  createdAt: string
}

export function RunbooksPage() {
  const { clusterId } = useParams<{ clusterId: string }>()
  const queryClient = useQueryClient()
  const toast = useToast()
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [toDelete, setToDelete] = useState<Runbook | null>(null)

  const queryKey = ['runbooks', clusterId]
  const { data, isLoading, isError, error } = useQuery<Runbook[]>({
    queryKey,
    queryFn: async () => (await api.get<Runbook[]>(`/clusters/${clusterId}/runbooks`)).data,
    enabled: !!clusterId,
  })

  const create = useMutation({
    mutationFn: async () =>
      (await api.post(`/clusters/${clusterId}/runbooks`, { title: title.trim(), content: content.trim() })).data,
    onSuccess: () => {
      toast.success('Runbook added')
      setTitle('')
      setContent('')
      queryClient.invalidateQueries({ queryKey })
    },
  })

  const remove = async () => {
    if (!toDelete) return
    try {
      await api.delete(`/clusters/${clusterId}/runbooks/${toDelete.id}`)
      toast.success(`Deleted "${toDelete.title}"`)
      queryClient.invalidateQueries({ queryKey })
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Delete failed'))
    }
  }

  return (
    <Layout>
      <PageHeader
        title="Runbooks"
        subtitle="Your own troubleshooting procedures — the AI cites these alongside its built-in knowledge"
        count={data?.length}
        noun="runbook"
      />

      <div className="rounded-lg border border-gray-200 dark:border-neutral-700 bg-white dark:bg-neutral-900 p-4 mb-4 space-y-3">
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="Title, e.g. Restarting the payments worker"
          className="w-full rounded-md border border-gray-300 dark:border-neutral-600 bg-white dark:bg-neutral-800
                     text-gray-900 dark:text-neutral-100 placeholder:text-gray-400 dark:placeholder:text-neutral-500 px-3 py-2 text-sm
                     focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
        />
        <textarea
          value={content}
          onChange={(e) => setContent(e.target.value)}
          rows={4}
          placeholder="Steps, context, gotchas — anything your AI should know when it sees a related problem in this cluster."
          className="w-full resize-y rounded-md border border-gray-300 dark:border-neutral-600 bg-white dark:bg-neutral-800
                     text-gray-900 dark:text-neutral-100 placeholder:text-gray-400 dark:placeholder:text-neutral-500 px-3 py-2 text-sm
                     focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
        />
        {create.isError && (
          <p className="text-sm text-red-600 dark:text-red-400">{apiErrorMessage(create.error, 'Could not save runbook')}</p>
        )}
        <div className="flex justify-end">
          <button
            onClick={() => create.mutate()}
            disabled={create.isPending || !title.trim() || !content.trim()}
            className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white
                       hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {create.isPending ? 'Saving…' : 'Add runbook'}
          </button>
        </div>
      </div>

      {isLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load runbooks: ${apiErrorMessage(error)}`} />}

      {data && data.length === 0 && (
        <div className="rounded-lg border border-dashed border-gray-200 dark:border-neutral-700 bg-white dark:bg-neutral-900 px-6 py-12 text-center">
          <p className="text-sm text-gray-400 dark:text-neutral-500">No runbooks yet for this cluster.</p>
        </div>
      )}

      {data && data.length > 0 && (
        <div className="space-y-3">
          {data.map((r) => (
            <div key={r.id} className="rounded-lg border border-gray-200 dark:border-neutral-700 bg-white dark:bg-neutral-900 p-4">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <p className="text-sm font-medium text-gray-900 dark:text-neutral-100">{r.title}</p>
                  <p className="text-xs text-gray-400 dark:text-neutral-500 mt-0.5">
                    {r.createdBy} · {formatAge(r.createdAt)} ago
                  </p>
                </div>
                <button
                  onClick={() => setToDelete(r)}
                  className="shrink-0 rounded-md border border-red-200 dark:border-red-500/30 px-2.5 py-1 text-xs font-medium
                             text-red-600 dark:text-red-400 hover:bg-red-50 transition-colors"
                >
                  Delete
                </button>
              </div>
              <p className="mt-2 text-sm text-gray-600 dark:text-neutral-400 whitespace-pre-wrap leading-relaxed">{r.content}</p>
            </div>
          ))}
        </div>
      )}

      <ConfirmDialog
        open={!!toDelete}
        title={`Delete runbook "${toDelete?.title ?? ''}"`}
        message="The AI will no longer be able to reference this runbook."
        confirmLabel="Delete"
        danger
        onConfirm={remove}
        onClose={() => setToDelete(null)}
      />
    </Layout>
  )
}
