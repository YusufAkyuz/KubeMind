import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'

type Verb = 'create' | 'update' | 'patch' | 'delete' | 'get'

interface NamespacePermissions {
  namespace: string
  kinds: Record<string, Record<Verb, boolean>>
  /** false when the cluster couldn't tell us — never hide anything on that basis. */
  resolved: boolean
}

/** Permissions are per-namespace, so these pseudo-namespaces can't be evaluated. */
function isRealNamespace(ns: string | undefined): ns is string {
  return !!ns && ns !== 'all' && ns !== '_'
}

/**
 * What the cluster says this kubeconfig may do in a namespace. Asked once per
 * cluster+namespace and cached — RBAC doesn't change mid-session in practice,
 * and a stale answer only affects which buttons look enabled; the cluster is
 * still the one enforcing it.
 */
export function useClusterPermissions(clusterId: string | undefined, ns: string | undefined) {
  return useQuery<NamespacePermissions>({
    queryKey: ['permissions', clusterId, ns],
    queryFn: async () =>
      (await api.get<NamespacePermissions>(
        `/clusters/${clusterId}/permissions`, { params: { namespace: ns } })).data,
    enabled: !!clusterId && isRealNamespace(ns),
    staleTime: 5 * 60_000,
    retry: false,
  })
}

export interface KindPermission {
  allowed: boolean
  /** Why it's refused, for a tooltip. null when allowed or unknown. */
  reason: string | null
}

const ALLOWED: KindPermission = { allowed: true, reason: null }

/**
 * Whether the cluster would accept this verb on this kind, here.
 *
 * Fails open on purpose: while loading, when the namespace can't be evaluated
 * ("all namespaces"), or when the cluster doesn't expose the API, this reports
 * allowed. Disabling a control the user actually has is a worse failure than
 * letting them discover a refusal — and the cluster still refuses it.
 */
export function useKindPermission(
  clusterId: string | undefined,
  ns: string | undefined,
  kind: string,
  verb: Verb,
): KindPermission {
  const { data } = useClusterPermissions(clusterId, ns)

  if (!data?.resolved) return ALLOWED

  const verbs = data.kinds[kind]
  if (!verbs || verbs[verb] === undefined) return ALLOWED
  if (verbs[verb]) return ALLOWED

  return {
    allowed: false,
    reason: `Your kubeconfig for this cluster cannot ${verb} ${kind}s in "${ns}".`,
  }
}
