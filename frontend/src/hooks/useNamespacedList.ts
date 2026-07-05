import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'

/**
 * Shared plumbing for namespaced resource list pages: reads clusterId/ns from
 * the route, guards the "no namespace selected" placeholder (`_`) and polls
 * the list endpoint. Keeps the eight resource pages down to columns + drawer.
 */
export function useNamespacedList<T>(resource: string, refetchMs = 20_000) {
  const { clusterId, ns } = useParams<{ clusterId: string; ns: string }>()
  const noNamespace = ns === '_'

  const query = useQuery<T[]>({
    queryKey: [resource, clusterId, ns],
    queryFn: async () =>
      (await api.get<T[]>(`/clusters/${clusterId}/namespaces/${ns}/${resource}`)).data,
    enabled: !!clusterId && !!ns && !noNamespace,
    refetchInterval: refetchMs,
  })

  return { clusterId, ns, noNamespace, ...query }
}

/** Empty-state shown when no namespace is selected yet. */
export function noNamespaceMessage(noun: string) {
  return `Select a namespace from the sidebar to view ${noun}.`
}
