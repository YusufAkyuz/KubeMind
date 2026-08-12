import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'

interface AppConfig {
  /** false when this deployment runs without cluster-admin: the Cluster Terminal,
   *  Node Shell and RBAC-object writes are switched off server-side. */
  privilegedFeatures: boolean
  /** true when kubemind.oidc.issuer-uri is configured server-side — see
   *  LoginPage, which only offers the SSO link when this is true. */
  oidcEnabled: boolean
  /** true when calls against the built-in cluster carry the caller's own
   *  Kubernetes identity. That cluster is then visible to everyone, and what
   *  each person sees inside it is the cluster's RBAC decision, not the app's —
   *  worth saying out loud in the UI, since "I can see it but it's empty" is
   *  otherwise indistinguishable from a broken connection. */
  impersonationEnabled: boolean
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
