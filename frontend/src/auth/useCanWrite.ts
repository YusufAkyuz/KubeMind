import { useAuth } from './AuthContext'

/**
 * Can the current user write (create/edit/delete) resources on this cluster?
 * ADMIN can write anywhere. A USER can write on any cluster returned by
 * `/api/clusters` for them, because that list only ever contains clusters
 * they registered themselves (self-service model — see ClusterService.list
 * on the backend) — except the built-in shared cluster (id 0), which stays
 * ADMIN-only since it's one identity shared by everyone, not a per-user
 * credential. Matches the backend's ClusterAccessService.canWrite.
 */
export function useCanWrite(clusterId: string | undefined): boolean {
  const { isAdmin } = useAuth()
  if (isAdmin) return true
  return clusterId !== undefined && clusterId !== '0'
}
