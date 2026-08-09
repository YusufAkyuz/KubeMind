import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, waitFor } from '@testing-library/react'
import { Sidebar } from './Sidebar'
import { renderWithProviders } from '../test/renderWithProviders'
import { mockGet } from '../test/mockApi'

// vi.mock factories are hoisted above imports, so they can't close over a
// module-scoped `const` declared normally (TDZ at hoist time) — vi.hoisted
// is the escape hatch: its return value is itself hoisted, so the factory
// below can safely reference it.
const { mockApi, mockNavigate } = vi.hoisted(() => ({
  mockApi: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  mockNavigate: vi.fn(),
}))

// A single mocked `api` shared by AuthProvider, Sidebar's own queries, and the
// permission hook it calls — see test/mockApi.ts.
vi.mock('../api/client', () => ({ api: mockApi, apiErrorMessage: () => 'error' }))

// Sidebar reads useNavigate() directly; spying on it (rather than inspecting
// MemoryRouter's history) is what lets a test assert "did NOT navigate" —
// history has no clean way to say nothing happened.
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => mockNavigate }
})

const USER_CLUSTER = { id: 5, name: 'eba-ex', status: 'APPROVED', builtIn: false, lastCheckOk: true }

function stubEndpoints(clusters: unknown[], isAdmin = false) {
  mockGet(mockApi, {
    '/auth/me': { username: 'bob', role: isAdmin ? 'ADMIN' : 'USER' },
    '/config': { privilegedFeatures: true },
    '/clusters': clusters,
    '/clusters/pending': [],
    '/clusters/0/permissions/terminals': { nodeShell: true, clusterTerminal: true },
    '/clusters/5/permissions/terminals': { nodeShell: true, clusterTerminal: true },
    '/clusters/5/namespaces': [{ name: 'default' }],
    '/clusters/5/namespaces/all/pods': [],
  })
}

beforeEach(() => {
  mockNavigate.mockClear()
  mockApi.get.mockReset()
})

describe('Sidebar — redirect-away-from-unreachable-cluster effect', () => {
  // Regression test for the bug fixed in ea6410a: the effect used to check the
  // clusterId URL param's fallback value ('0', the built-in ADMIN-only
  // cluster) instead of whether the route actually named a cluster at all. A
  // USER's cluster list never contains '0', so every non-cluster page
  // (Clusters, Users, Audit) got redirected away.
  it('does not redirect a USER away from a non-cluster-scoped page', async () => {
    stubEndpoints([USER_CLUSTER])
    renderWithProviders(<Sidebar />, { route: '/settings/clusters' })

    // Let the clusters query resolve and the effect run.
    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith('/clusters'))
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('does not redirect while sitting on a cluster the user does own', async () => {
    stubEndpoints([USER_CLUSTER])
    renderWithProviders(<Sidebar />, { route: '/clusters/5/nodes' })

    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith('/clusters'))
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('does redirect away from a cluster id the user no longer owns', async () => {
    stubEndpoints([USER_CLUSTER])
    renderWithProviders(<Sidebar />, { route: '/clusters/999/nodes' })

    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/clusters/5/nodes', { replace: true }))
  })
})

describe('Sidebar — namespaces query guard', () => {
  // Regression test for the same clusterId-defaults-to-'0' bug class: this
  // query originally had no `enabled` guard at all, so it fired
  // GET /clusters/0/namespaces (a 403 for any USER) on every single page.
  it('never requests namespaces for the fallback cluster off a cluster page', async () => {
    stubEndpoints([USER_CLUSTER])
    renderWithProviders(<Sidebar />, { route: '/settings/clusters' })

    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith('/clusters'))
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })

    expect(mockApi.get).not.toHaveBeenCalledWith(expect.stringContaining('/namespaces'))
  })

  it('does request namespaces once a real cluster is in the URL', async () => {
    stubEndpoints([USER_CLUSTER])
    renderWithProviders(<Sidebar />, { route: '/clusters/5/nodes' })

    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith('/clusters/5/namespaces'))
  })
})
