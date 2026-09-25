import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useCanWrite } from './useCanWrite'

const { authState, configState } = vi.hoisted(() => ({
  authState: { isAdmin: false },
  configState: { privilegedFeatures: true },
}))
vi.mock('./AuthContext', () => ({ useAuth: () => authState }))
vi.mock('../hooks/useAppConfig', () => ({
  usePrivilegedFeatures: () => configState.privilegedFeatures,
}))

beforeEach(() => {
  authState.isAdmin = false
  configState.privilegedFeatures = true
})

/**
 * Every resource page decides whether to offer its write controls through this
 * one hook, so a mistake here is a mistake on all of them at once — and it had
 * no tests. It mirrors the backend's ClusterAccessService.canWrite; the two
 * disagreeing is what let the Helm pages drift into gating on `isAdmin` while
 * the API allowed a USER through.
 */
describe('useCanWrite', () => {
  const write = (clusterId: string | undefined, kind?: string) =>
    renderHook(() => useCanWrite(clusterId, kind)).result.current

  it('lets an admin write anywhere, the built-in cluster included', () => {
    authState.isAdmin = true

    expect(write('0')).toBe(true)
    expect(write('7')).toBe(true)
  })

  /** A USER's cluster list only ever holds clusters they registered themselves. */
  it('lets a user write on a cluster they registered', () => {
    expect(write('7')).toBe(true)
  })

  /**
   * Cluster 0 is one identity shared by everyone rather than anyone's own
   * credential, so there is no per-user scope to write under.
   */
  it('keeps a user out of the shared built-in cluster', () => {
    expect(write('0')).toBe(false)
  })

  it('says no before the cluster is known', () => {
    expect(write(undefined)).toBe(false)
  })

  /**
   * On a restricted-mode install the API server refuses RBAC kinds outright, so
   * offering the button would only produce a guaranteed 403.
   */
  it('hides RBAC kinds when the deployment has no privileged features', () => {
    configState.privilegedFeatures = false

    expect(write('7', 'Role')).toBe(false)
    expect(write('7', 'Deployment')).toBe(true)
  })

  it('allows RBAC kinds when privileged features are on', () => {
    expect(write('7', 'ClusterRoleBinding')).toBe(true)
  })

  /** Not even an admin can act on what the cluster will refuse. */
  it('applies the restricted-mode rule to admins too', () => {
    authState.isAdmin = true
    configState.privilegedFeatures = false

    expect(write('0', 'Role')).toBe(false)
  })
})
