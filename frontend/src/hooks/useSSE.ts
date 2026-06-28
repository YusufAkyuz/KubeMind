import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'

/**
 * Opens an SSE connection to `url` and writes received "init" and "update"
 * events directly into the TanStack Query cache under `queryKey`.
 * The connection closes when the component unmounts or `url` changes.
 */
export function useSSE<T>(url: string | null, queryKey: unknown[]) {
  const queryClient = useQueryClient()
  const key = JSON.stringify(queryKey)

  useEffect(() => {
    if (!url) return

    const es = new EventSource(url, { withCredentials: true })

    const handle = (e: MessageEvent) => {
      try {
        queryClient.setQueryData<T>(queryKey, JSON.parse(e.data as string))
      } catch {
        // malformed payload — ignore
      }
    }

    es.addEventListener('init', handle)
    es.addEventListener('update', handle)
    es.onerror = () => es.close()

    return () => es.close()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, key])
}
