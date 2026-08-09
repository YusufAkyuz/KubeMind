import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useTerminalPermission, useKindPermission } from './useClusterPermissions'

const { mockApi } = vi.hoisted(() => ({
  mockApi: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}))
vi.mock('../api/client', () => ({ api: mockApi, apiErrorMessage: () => 'error' }))

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

beforeEach(() => {
  mockApi.get.mockReset()
})

// These hooks back every disabled-terminal-button and disabled-CRUD-button in
// the app (NodesPage's Node shell button, Sidebar's Cluster Terminal button,
// every create/edit/delete action gated by useKindPermission). The contract
// they all share, documented on both hooks: never HIDE a control the caller
// actually has. A slow or failed permission check must read as "allowed",
// because the cluster's own RBAC is the real enforcement regardless of what
// the button shows — a wrongly-disabled button is a worse failure than a
// click that gets refused.
describe('useTerminalPermission — fail-open contract', () => {
  it('reports allowed while the permission check is still loading', () => {
    mockApi.get.mockImplementation(() => new Promise(() => {})) // never resolves
    const { result } = renderHook(() => useTerminalPermission('5', 'nodeShell'), { wrapper })
    expect(result.current).toBe(true)
  })

  it('reports allowed when the permission endpoint errors', async () => {
    mockApi.get.mockRejectedValue(new Error('502'))
    const { result } = renderHook(() => useTerminalPermission('5', 'nodeShell'), { wrapper })
    await waitFor(() => expect(mockApi.get).toHaveBeenCalled())
    await waitFor(() => expect(result.current).toBe(true))
  })

  it('reports the cluster refusal once it actually answers', async () => {
    mockApi.get.mockResolvedValue({ data: { nodeShell: false, clusterTerminal: true } })
    const { result } = renderHook(() => useTerminalPermission('5', 'nodeShell'), { wrapper })
    await waitFor(() => expect(result.current).toBe(false))
  })

  it('reports the cluster grant once it actually answers', async () => {
    mockApi.get.mockResolvedValue({ data: { nodeShell: false, clusterTerminal: true } })
    const { result } = renderHook(() => useTerminalPermission('5', 'clusterTerminal'), { wrapper })
    await waitFor(() => expect(result.current).toBe(true))
  })
})

describe('useKindPermission — fail-open contract', () => {
  it('allows while the namespace permission check is still loading', () => {
    mockApi.get.mockImplementation(() => new Promise(() => {}))
    const { result } = renderHook(() => useKindPermission('5', 'dev-team', 'Pod', 'delete'), { wrapper })
    expect(result.current.allowed).toBe(true)
  })

  it('allows when the cluster could not resolve permissions at all (resolved: false)', async () => {
    mockApi.get.mockResolvedValue({ data: { namespace: 'dev-team', kinds: {}, resolved: false } })
    const { result } = renderHook(() => useKindPermission('5', 'dev-team', 'Pod', 'delete'), { wrapper })
    await waitFor(() => expect(mockApi.get).toHaveBeenCalled())
    await waitFor(() => expect(result.current.allowed).toBe(true))
  })

  it('refuses with a reason when the cluster explicitly says no', async () => {
    mockApi.get.mockResolvedValue({
      data: { namespace: 'dev-team', resolved: true, kinds: { Pod: { delete: false, get: true, create: true, update: true, patch: true } } },
    })
    const { result } = renderHook(() => useKindPermission('5', 'dev-team', 'Pod', 'delete'), { wrapper })
    await waitFor(() => expect(result.current.allowed).toBe(false))
    expect(result.current.reason).toContain('dev-team')
  })
})
