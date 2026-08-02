import { useAuth } from './AuthContext'
import { usePrivilegedFeatures } from '../hooks/useAppConfig'
import { isRbacKind } from '../utils/resourceKinds'

/**
 * Can the current user write (create/edit/delete) resources on this cluster?
 * ADMIN can write anywhere. A USER can write on any cluster returned by
 * `/api/clusters` for them, because that list only ever contains clusters
 * they registered themselves (self-service model — see ClusterService.list
 * on the backend) — except the built-in shared cluster (id 0), which stays
 * ADMIN-only since it's one identity shared by everyone, not a per-user
 * credential. Matches the backend's ClusterAccessService.canWrite.
 *
 * Pass `kind` where the control is kind-specific: on a deployment running
 * without cluster-admin the RBAC kinds are refused server-side, so offering
 * the button would just produce a guaranteed 403.
 */
export function useCanWrite(clusterId: string | undefined, kind?: string): boolean {
  const { isAdmin } = useAuth()
  const privilegedFeatures = usePrivilegedFeatures()

  if (kind && isRbacKind(kind) && !privilegedFeatures) return false
  if (isAdmin) return true
  return clusterId !== undefined && clusterId !== '0'
}
