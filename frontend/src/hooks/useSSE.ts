import { useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'

/**
 * Opens an SSE connection to `url` and writes received "init" and "update"
 * events directly into the TanStack Query cache under `queryKey`. Returns the
 * cluster-unreachable message from a server-sent "error" event, if any (see
 * WatchController.sendErrorAndComplete on the backend) — callers combine this
 * with their REST query's own isError/error the same way, so a stream that
 * dies immediately doesn't just spin on "Loading…" forever.
 * The connection closes when the component unmounts or `url` changes.
 */
export function useSSE<T>(url: string | null, queryKey: unknown[]): string | null {
  const queryClient = useQueryClient()
  const key = JSON.stringify(queryKey)
  const [streamError, setStreamError] = useState<string | null>(null)

  useEffect(() => {
    if (!url) return
    setStreamError(null)

    const es = new EventSource(url, { withCredentials: true })

    const handle = (e: MessageEvent) => {
      try {
        queryClient.setQueryData<T>(queryKey, JSON.parse(e.data as string))
        setStreamError(null)
      } catch {
        // malformed payload — ignore
      }
    }

    es.addEventListener('init', handle)
    es.addEventListener('update', handle)
    // Backend sends this when the cluster was unreachable before the stream even
    // started (see WatchController.sendErrorAndComplete) — surface it the same way
    // a failed REST fetch would, instead of leaving the page on "Loading…" forever.
    es.addEventListener('error', (e: MessageEvent) => {
      try {
        const parsed = JSON.parse(e.data as string)
        setStreamError(parsed?.error ?? 'Live updates disconnected.')
      } catch {
        // not a JSON payload — a plain connection-level error event, nothing to parse
      }
      es.close()
    })
    es.onerror = () => es.close()

    return () => es.close()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, key])

  return streamError
}
