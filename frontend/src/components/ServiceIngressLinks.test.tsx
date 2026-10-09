import { describe, it, expect, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { ServiceIngressLinks } from './ServiceIngressLinks'
import { renderWithProviders } from '../test/renderWithProviders'
import { mockGet } from '../test/mockApi'

const { mockApi } = vi.hoisted(() => ({
  mockApi: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}))
vi.mock('../api/client', () => ({ api: mockApi, apiErrorMessage: () => 'error' }))

const ME = { '/auth/me': { username: 'bob', role: 'USER' } }
const LINKS_PATH = '/clusters/0/namespaces/argocd/services/argocd-server/ingress-links'

beforeEach(() => {
  mockApi.get.mockReset()
})

/** For a Service an Ingress already exposes, the real URL beats the tunnel. */
describe('ServiceIngressLinks', () => {
  it('links to every URL an Ingress serves the service on, in a new tab', async () => {
    mockGet(mockApi, {
      ...ME,
      [LINKS_PATH]: [
        { ingress: 'argocd', url: 'https://argocd.my.kubernetes/' },
        { ingress: 'argocd', url: 'http://plain.my.kubernetes/ui' },
      ],
    })
    renderWithProviders(<ServiceIngressLinks clusterId="0" ns="argocd" name="argocd-server" />)

    const first = await screen.findByRole('link', { name: /Open argocd\.my\.kubernetes$/ })
    expect(first).toHaveAttribute('href', 'https://argocd.my.kubernetes/')
    expect(first).toHaveAttribute('target', '_blank')
    expect(first).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getByRole('link', { name: /Open plain\.my\.kubernetes\/ui/ }))
      .toHaveAttribute('href', 'http://plain.my.kubernetes/ui')
  })

  it('renders nothing when no Ingress routes to the service', async () => {
    mockGet(mockApi, { ...ME, [LINKS_PATH]: [] })
    renderWithProviders(<ServiceIngressLinks clusterId="0" ns="argocd" name="argocd-server" />)

    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith(LINKS_PATH))
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('stays out of the way when the lookup fails', async () => {
    mockGet(mockApi, { ...ME, [LINKS_PATH]: new Error('403') })
    renderWithProviders(<ServiceIngressLinks clusterId="0" ns="argocd" name="argocd-server" />)

    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith(LINKS_PATH))
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('never puts a non-http URL into an href', async () => {
    mockGet(mockApi, { ...ME, [LINKS_PATH]: [{ ingress: 'x', url: 'javascript:alert(1)' }] })
    renderWithProviders(<ServiceIngressLinks clusterId="0" ns="argocd" name="argocd-server" />)

    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith(LINKS_PATH))
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})
