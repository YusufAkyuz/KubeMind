import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'

interface AppConfig {
  /** false when this deployment runs without cluster-admin: the Cluster Terminal,
   *  Node Shell and RBAC-object writes are switched off server-side. */
  privilegedFeatures: boolean
}

/**
 * Install-level capabilities. This is fixed for the lifetime of the deployment,
 * so it's fetched once and never refetched.
 */
export function useAppConfig() {
  return useQuery<AppConfig>({
    queryKey: ['app-config'],
    queryFn: async () => (await api.get<AppConfig>('/config')).data,
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
  })
}

/**
 * Whether this deployment offers the cluster-admin-only surfaces at all.
 *
 * Defaults to false while the config is still loading, so a slow response can
 * never flash a privileged control the server would reject anyway. Hiding these
 * is a courtesy to the user — the endpoints refuse independently (see
 * PrivilegedFeatures on the backend), so this is not the security boundary.
 */
export function usePrivilegedFeatures(): boolean {
  const { data } = useAppConfig()
  return data?.privilegedFeatures ?? false
}
