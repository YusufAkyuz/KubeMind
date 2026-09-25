import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, apiErrorMessage } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { ErrorBanner } from '../components/ErrorBanner'
import { formatAge } from '../utils/format'

interface AuditEntry {
  id: number
  username: string
  action: string
  resourceRef: string
  payload: string | null
  result: string
  createdAt: string
}

interface AuditPage {
  entries: AuditEntry[]
  page: number
  totalPages: number
  totalElements: number
}

const COLUMNS = [
  { key: 'time', label: 'When' },
  { key: 'user', label: 'User' },
  { key: 'action', label: 'Action' },
  { key: 'resource', label: 'Resource' },
  { key: 'result', label: 'Result' },
]

const PAGE_SIZE = 10

export function AuditPage() {
  const [page, setPage] = useState(0)

  const { data, isLoading, isError, error } = useQuery<AuditPage>({
    queryKey: ['audit', page],
    queryFn: async () =>
      (await api.get<AuditPage>('/audit', { params: { page, size: PAGE_SIZE } })).data,
    refetchInterval: 30_000,
  })

  return (
    <Layout>
      <PageHeader
        title="Audit log"
        subtitle="Every write action against the cluster, successful or not"
        count={data?.totalElements}
        noun="entry"
      />

      {isLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load audit log: ${apiErrorMessage(error)}`} />}

      {data && data.entries.length === 0 && (
        <div className="rounded-lg border border-dashed border-gray-200 dark:border-neutral-700 bg-white dark:bg-neutral-900 px-6 py-12 text-center">
          <p className="text-sm text-gray-400 dark:text-neutral-500">No write actions recorded yet.</p>
        </div>
      )}

      {data && data.entries.length > 0 && (
        <>
          <Table columns={COLUMNS}>
            {data.entries.map((e) => (
              <Tr key={e.id}>
                <Td className="text-gray-400 dark:text-neutral-500 tabular-nums whitespace-nowrap">
                  {formatAge(e.createdAt)} ago
                </Td>
                <Td className="font-medium text-gray-900 dark:text-neutral-100">{e.username}</Td>
                <Td>
                  <span className="font-mono text-xs text-gray-600 dark:text-neutral-400">{e.action}</span>
                </Td>
                <Td className="font-mono text-xs text-gray-500 dark:text-neutral-400">{e.resourceRef}</Td>
                <Td>
                  {e.result === 'SUCCESS' ? (
                    <span className="text-xs font-medium text-emerald-600 dark:text-emerald-400">SUCCESS</span>
                  ) : (
                    <span className="text-xs font-medium text-red-600 dark:text-red-400" title={e.result}>
                      FAILED
                    </span>
                  )}
                </Td>
              </Tr>
            ))}
          </Table>

          {data.totalPages > 1 && (
            <div className="flex items-center justify-between mt-4">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className="rounded-md border border-gray-300 dark:border-neutral-600 px-3 py-1.5 text-xs text-gray-600 dark:text-neutral-400
                           hover:bg-gray-50 dark:hover:bg-neutral-800 disabled:opacity-40 transition-colors"
              >
                Previous
              </button>
              <span className="text-xs text-gray-400 dark:text-neutral-500">
                Page {data.page + 1} of {data.totalPages}
              </span>
              <button
                onClick={() => setPage((p) => p + 1)}
                disabled={data.page + 1 >= data.totalPages}
                className="rounded-md border border-gray-300 dark:border-neutral-600 px-3 py-1.5 text-xs text-gray-600 dark:text-neutral-400
                           hover:bg-gray-50 dark:hover:bg-neutral-800 disabled:opacity-40 transition-colors"
              >
                Next
              </button>
            </div>
          )}
        </>
      )}
    </Layout>
  )
}
