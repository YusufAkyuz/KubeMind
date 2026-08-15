import { describe, it, expect, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { LoginPage } from './LoginPage'
import { renderWithProviders } from '../test/renderWithProviders'
import { mockGet } from '../test/mockApi'

const { mockApi, mockNavigate } = vi.hoisted(() => ({
  mockApi: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  mockNavigate: vi.fn(),
}))
vi.mock('../api/client', () => ({ api: mockApi, apiErrorMessage: () => 'error' }))
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => mockNavigate }
})

beforeEach(() => {
  mockApi.get.mockReset()
  mockApi.post.mockReset()
  mockNavigate.mockClear()
})

// LoginPage runs before AuthContext resolves a session — GET /config must be
// reachable unauthenticated (see SecurityConfig's permitAll on it) for this
// to work at all, otherwise the SSO link could never appear before login.
describe('LoginPage — SSO link visibility', () => {
  it('shows no SSO link when the backend reports OIDC is not configured', async () => {
    mockGet(mockApi, { '/auth/me': new Error('401'), '/config': { privilegedFeatures: false, oidcEnabled: false } })
    renderWithProviders(<LoginPage />, { route: '/login' })

    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith('/config'))
    expect(screen.queryByText('Sign in with SSO')).not.toBeInTheDocument()
  })

  it('shows the SSO link pointing at the fixed OIDC authorization endpoint when enabled', async () => {
    mockGet(mockApi, { '/auth/me': new Error('401'), '/config': { privilegedFeatures: false, oidcEnabled: true } })
    renderWithProviders(<LoginPage />, { route: '/login' })

    const link = await screen.findByText('Sign in with SSO')
    expect(link.closest('a')).toHaveAttribute('href', '/oauth2/authorization/oidc')
  })
})

/**
 * An identity provider outage used to stop the backend booting entirely. Now it
 * degrades to this: the page still loads, says what happened, and — the part
 * that matters — leaves the password form working, since that is the documented
 * way back in when SSO is broken.
 */
describe('LoginPage — identity provider unreachable', () => {
  it('explains an unavailable identity provider and keeps the password form usable', async () => {
    mockGet(mockApi, { '/auth/me': new Error('401'), '/config': { privilegedFeatures: false, oidcEnabled: true } })
    mockApi.post.mockResolvedValue({ data: { username: 'admin', role: 'ADMIN' } })
    renderWithProviders(<LoginPage />, { route: '/login?sso=unavailable' })

    expect(await screen.findByText(/couldn't reach your identity provider/i)).toBeInTheDocument()

    await userEvent.type(screen.getByPlaceholderText('Username'), 'admin')
    await userEvent.type(screen.getByPlaceholderText('Password'), 'hunter2')
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }))

    await waitFor(() =>
      expect(mockApi.post).toHaveBeenCalledWith('/auth/login', { username: 'admin', password: 'hunter2' }))
  })

  it('reports a round trip that did not complete', async () => {
    mockGet(mockApi, { '/auth/me': new Error('401'), '/config': { privilegedFeatures: false, oidcEnabled: true } })
    renderWithProviders(<LoginPage />, { route: '/login?sso=failed' })

    expect(await screen.findByText(/did not complete/i)).toBeInTheDocument()
  })

  it('shows no such message on a normal visit', async () => {
    mockGet(mockApi, { '/auth/me': new Error('401'), '/config': { privilegedFeatures: false, oidcEnabled: true } })
    renderWithProviders(<LoginPage />, { route: '/login' })

    await screen.findByText('Sign in with SSO')
    expect(screen.queryByText(/identity provider/i)).not.toBeInTheDocument()
  })
})

describe('LoginPage — password form', () => {
  it('still submits via the local login endpoint regardless of OIDC state', async () => {
    mockGet(mockApi, { '/auth/me': new Error('401'), '/config': { privilegedFeatures: false, oidcEnabled: true } })
    mockApi.post.mockResolvedValue({ data: { username: 'bob', role: 'USER' } })
    renderWithProviders(<LoginPage />, { route: '/login' })

    await userEvent.type(screen.getByPlaceholderText('Username'), 'bob')
    await userEvent.type(screen.getByPlaceholderText('Password'), 'hunter2')
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }))

    await waitFor(() => expect(mockApi.post).toHaveBeenCalledWith('/auth/login', { username: 'bob', password: 'hunter2' }))
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/'))
  })
})
