import { useMutation } from '@tanstack/react-query'
import axios from 'axios'
import { api } from '../api/client'
import { formatAge } from '../utils/format'
import { renderLiteMarkdown } from '../utils/markdownLite'
import { AiFeedbackButtons } from './AiFeedbackButtons'

interface ExplainResponse {
  explanation: string
  cached: boolean
  model: string
  createdAt: string
  stateHash: string
}

interface Props {
  clusterId: string
  namespace: string
  /** "Pod" uses its own richer endpoint (includes logs); every other kind uses the generic one. */
  kind: string
  name: string
}

export function ExplainPanel({ clusterId, namespace, kind, name }: Props) {
  const path = kind === 'Pod'
    ? `/clusters/${clusterId}/namespaces/${namespace}/pods/${name}/explain`
    : `/clusters/${clusterId}/namespaces/${namespace}/resources/${kind}/${name}/explain`

  const mutation = useMutation<ExplainResponse, unknown>({
    mutationFn: async () => (await api.post<ExplainResponse>(path)).data,
  })

  const errorMessage = mutation.isError
    ? axios.isAxiosError(mutation.error) && mutation.error.response?.data?.error
      ? String(mutation.error.response.data.error)
      : 'Something went wrong while contacting the AI service.'
    : null

  return (
    <div className="rounded-lg border border-gray-200 dark:border-neutral-700 overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 bg-gray-50 dark:bg-neutral-800/60 border-b border-gray-200 dark:border-neutral-700">
        <div>
          <p className="text-sm font-medium text-gray-900 dark:text-neutral-100">AI Diagnosis</p>
          {mutation.data && (
            <p className="text-xs text-gray-400 dark:text-neutral-500 mt-0.5">
              {mutation.data.model}
              {mutation.data.cached && ` · cached ${formatAge(mutation.data.createdAt)} ago`}
            </p>
          )}
        </div>
        <button
          onClick={() => mutation.mutate()}
          disabled={mutation.isPending}
          className="shrink-0 rounded-md bg-blue-600 px-3 py-1.5 text-xs font-medium text-white
                     hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {mutation.isPending ? 'Analyzing…' : mutation.data ? 'Re-analyze' : 'Explain'}
        </button>
      </div>

      {/* Body */}
      <div className="px-4 py-3">
        {!mutation.data && !mutation.isPending && !mutation.isError && (
          <p className="text-sm text-gray-400 dark:text-neutral-500">
            Ask the AI to analyze this {kind.toLowerCase()}'s state{kind === 'Pod' ? ', events, and recent logs' : ' and recent events'}.
          </p>
        )}

        {mutation.isPending && (
          <div className="space-y-2 animate-pulse py-1">
            <div className="h-3 bg-gray-100 dark:bg-neutral-700 rounded w-4/5" />
            <div className="h-3 bg-gray-100 dark:bg-neutral-700 rounded w-full" />
            <div className="h-3 bg-gray-100 dark:bg-neutral-700 rounded w-3/5" />
          </div>
        )}

        {errorMessage && (
          <p className="text-sm text-red-600 dark:text-red-400">{errorMessage}</p>
        )}

        {mutation.data && (
          <>
            <div className="space-y-2">{renderLiteMarkdown(mutation.data.explanation)}</div>
            <div className="mt-3 pt-3 border-t border-gray-100 dark:border-neutral-800 flex items-center justify-between">
              <p className="text-xs text-gray-400 dark:text-neutral-500">AI-generated — verify before acting.</p>
              <AiFeedbackButtons clusterId={clusterId} surface="EXPLAIN" contextHash={mutation.data.stateHash} />
            </div>
          </>
        )}
      </div>
    </div>
  )
}
