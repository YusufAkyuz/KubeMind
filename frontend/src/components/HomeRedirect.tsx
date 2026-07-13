import { Navigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Cluster } from '../types/k8s'

/**
 * `/` used to hard-redirect to `/clusters/0/nodes` (the built-in cluster).
 * That breaks for a self-service USER: they never see the built-in cluster,
 * and may have zero approved clusters of their own yet. Pick the first
 * usable one, or send them to the Clusters page to add one.
 */
export function HomeRedirect() {
  const { data, isLoading } = useQuery<Cluster[]>({
    queryKey: ['clusters'],
    queryFn: async () => (await api.get<Cluster[]>('/clusters')).data,
  })

  if (isLoading) return null

  const usable = (data ?? []).find((c) => c.builtIn || c.status === 'APPROVED')
  return <Navigate to={usable ? `/clusters/${usable.id}/nodes` : '/settings/clusters'} replace />
}
