import { describe, it, expect, vi, beforeEach, afterEach, type MockInstance } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ForwardServiceButton } from './ForwardServiceButton'
import { renderWithProviders } from '../test/renderWithProviders'
import { mockGet } from '../test/mockApi'

const { mockApi } = vi.hoisted(() => ({
  mockApi: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}))
vi.mock('../api/client', () => ({ api: mockApi, apiErrorMessage: () => 'Service has no selector' }))

const FORWARD_PATH = '/clusters/0/namespaces/monitoring/services/grafana/forward'

let openSpy: MockInstance<typeof window.open>

beforeEach(() => {
  mockApi.get.mockReset()
  mockApi.post.mockReset()
  mockGet(mockApi, { '/auth/me': { username: 'bob', role: 'ADMIN' } })
  openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
})

afterEach(() => {
  openSpy.mockRestore()
})

/** Forward opens wherever the backend says this install serves sessions. */
describe('ForwardServiceButton', () => {
  it("opens the session's own origin through its handoff link", async () => {
    const url = 'http://0b1e2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d.pf.kubemind.my.kubernetes/__kubemind/handoff?token=t'
    mockApi.post.mockResolvedValue({ data: { sessionId: '0b1e2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d', url } })
    renderWithProviders(<ForwardServiceButton clusterId="0" ns="monitoring" name="grafana" />)

    await userEvent.click(screen.getByRole('button', { name: /Forward/ }))

    await waitFor(() => expect(openSpy).toHaveBeenCalledWith(url, '_blank', 'noopener,noreferrer'))
    expect(mockApi.post).toHaveBeenCalledWith(FORWARD_PATH)
  })

  it('opens the path under this origin when the install has no forward domain', async () => {
    mockApi.post.mockResolvedValue({ data: { sessionId: 's1', url: '/api/port-forward/s1/' } })
    renderWithProviders(<ForwardServiceButton clusterId="0" ns="monitoring" name="grafana" />)

    await userEvent.click(screen.getByRole('button', { name: /Forward/ }))

    await waitFor(() => expect(openSpy).toHaveBeenCalledWith('/api/port-forward/s1/', '_blank', 'noopener,noreferrer'))
  })

  it('opens nothing and says why when the forward fails', async () => {
    mockApi.post.mockRejectedValue(new Error('400'))
    renderWithProviders(<ForwardServiceButton clusterId="0" ns="monitoring" name="grafana" />)

    await userEvent.click(screen.getByRole('button', { name: /Forward/ }))

    expect(await screen.findByText(/Service has no selector/)).toBeInTheDocument()
    expect(openSpy).not.toHaveBeenCalled()
  })
})
