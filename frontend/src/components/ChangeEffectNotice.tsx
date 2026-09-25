import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { ChangeEffect, ClusterInsights } from '../types/k8s'

interface Props {
  clusterId: string
  /** "Kind/namespace/name" of the resource whose drawer this is. */
  resourceRef: string
}

/**
 * Shows, inside a resource's own drawer, that a change made through KubeMind was
 * followed by warnings on it — the "what changed?" question answered where
 * someone is already looking at the broken thing.
 *
 * Shares the cluster-insights query rather than adding an endpoint: the effects
 * are a small list computed by the same background job, and one cached request
 * serves every drawer on the page.
 */
export function ChangeEffectNotice({ clusterId, resourceRef }: Props) {
  const { data } = useQuery<ClusterInsights>({
    queryKey: ['cluster-insights', clusterId],
    queryFn: async () => (await api.get<ClusterInsights>(`/clusters/${clusterId}/cluster-insights`)).data,
    enabled: !!clusterId,
    staleTime: 60_000,
  })

  // Either side of the link is worth showing here: the drawer may be open on the
  // object that was changed, or on the pod that started complaining because of it.
  const effects = (data?.changeEffects ?? []).filter(
    (e) => e.resourceRef === resourceRef || e.warningSignature === resourceRef,
  )
  if (effects.length === 0) return null

  return (
    <div className="mb-3 rounded-md border border-amber-200 dark:border-amber-500/30 bg-amber-50 dark:bg-amber-500/10 px-3 py-2">
      <p className="text-xs font-semibold text-amber-800 dark:text-amber-300 mb-1">
        A recent change was followed by warnings here
      </p>
      <ul className="space-y-1.5">
        {effects.slice(0, 3).map((e, i) => (
          <li key={i} className="text-xs text-amber-900 dark:text-amber-200">
            <span className="font-mono">{e.warningReason}</span>
            {e.warningSignature !== resourceRef && (
              <span className="text-amber-700 dark:text-amber-400"> on <span className="font-mono">{e.warningSignature}</span></span>
            )}
            <span className="block text-amber-700 dark:text-amber-400">
              started {e.minutesAfter} min after {e.action}{' '}
              <span className="font-mono">{e.resourceRef}</span> by {e.username}
            </span>
            {releaseLink(clusterId, e)}
          </li>
        ))}
      </ul>
      <p className="mt-1.5 text-[10px] text-amber-700 dark:text-amber-400">
        Timing only — check before assuming the change is the cause.
      </p>
    </div>
  )
}

/**
 * Helm changes are the ones with a real undo, so they get a way back to it.
 *
 * Deliberately a link to the release rather than a rollback button here: this
 * notice is a coincidence in time, not a verdict, and a one-click rollback
 * beside it would invite acting on a guess. The release page shows the full
 * revision history and asks for confirmation — the right place to decide.
 */
function releaseLink(clusterId: string, effect: ChangeEffect) {
  const parts = effect.resourceRef.split('/')
  if (parts[0] !== 'HelmRelease' || parts.length < 3) return null
  return (
    <Link
      to={`/clusters/${clusterId}/namespaces/${parts[1]}/helm/releases`}
      className="inline-block mt-0.5 text-[11px] font-medium text-amber-800 dark:text-amber-300 underline underline-offset-2"
    >
      Review this release's history →
    </Link>
  )
}
